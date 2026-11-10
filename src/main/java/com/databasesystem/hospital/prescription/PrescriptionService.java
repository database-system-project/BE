package com.databasesystem.hospital.prescription;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;

import static com.databasesystem.hospital.common.Values.*;

@Service
@Transactional(readOnly = true)
public class PrescriptionService {
    private final Database db;
    private final ClinicalRepository clinical;
    private final Clock clock;
    private final ObjectMapper mapper;

    public PrescriptionService(Database db, ClinicalRepository clinical, Clock clock, ObjectMapper mapper) {
        this.db = db; this.clinical = clinical; this.clock = clock; this.mapper = mapper;
    }

    public Map<String, Object> medications(HospitalPrincipal user, String keyword) {
        user.doctor();
        String term = clean(keyword, 200);
        return Map.of("items", db.rows("""
                SELECT * FROM medicine WHERE medicine_active
                  AND (position(lower(:term) in lower(product_name)) > 0 OR item_seq = :term)
                ORDER BY product_name, medicine_id
                """, Map.of("term", term)).stream().map(Views::medication).toList());
    }

    public Map<String, Object> check(HospitalPrincipal user, long visitId, List<Long> ids) {
        Map<String, Object> visit = clinical.doctorVisit(user, visitId, false);
        clinical.inProgress(visit);
        validate(ids);
        return Map.of("warnings", warnings(visit, new TreeSet<>(ids), LocalDate.now(clock.withZone(SEOUL)))
                .stream().map(Warning::response).toList());
    }

    @Transactional
    public Map<String, Object> save(HospitalPrincipal user, long visitId, PrescriptionController.Save request) {
        Map<String, Object> visit = clinical.doctorVisit(user, visitId, true);
        clinical.inProgress(visit);
        List<Long> ids = request.getItems().stream().map(PrescriptionController.Item::medicationId).toList();
        validate(ids);
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        TreeSet<Long> locked = new TreeSet<>(ids);
        locked.addAll(currentMedications(id(visit, "patient_id"), today));
        db.rows("SELECT medicine_id FROM medicine WHERE medicine_id IN (:ids) ORDER BY medicine_id FOR SHARE", Map.of("ids", locked));
        validate(ids);
        List<Warning> warnings = warnings(visit, new TreeSet<>(ids), today);
        Map<String, String> reasons = new HashMap<>();
        for (var override : request.getOverrides()) {
            if (reasons.containsKey(override.warningId())) { throw ApiException.badRequest(); }
            reasons.put(override.warningId(), override.reason());
        }
        Set<String> expected = new HashSet<>();
        warnings.forEach(w -> expected.add((String) w.response().get("warningId")));
        if (!expected.equals(reasons.keySet()) || reasons.values().stream().anyMatch(r -> r == null || r.isBlank())) {
            throw new ApiException(409, "DUR_OVERRIDE_REQUIRED", "최신 금기 경고와 예외 처방 사유를 확인하세요.",
                    Map.of("warnings", warnings.stream().map(Warning::response).toList()));
        }
        long prescription = db.insert("""
                INSERT INTO prescription(visit_id, prescribed_at, prescription_status)
                VALUES (:visit, :now, 'ACTIVE') RETURNING prescription_id
                """, map("visit", visitId, "now", clock.instant()));
        for (var item : request.getItems()) {
            db.update("""
                    INSERT INTO prescription_item(prescription_id, medicine_id, dose_amount, dose_unit,
                        times_per_day, interval_days, total_days, timing, directions)
                    VALUES (:rx, :medicine, :dose, :unit, :times, :interval, :days, :timing, :instructions)
                    """, map("rx", prescription, "medicine", item.medicationId(), "dose", item.dosePerIntake(),
                    "unit", item.doseUnit().strip(), "times", item.timesPerDay(), "interval", item.intervalDays(),
                    "days", item.durationDays(), "timing", item.timing().name(), "instructions", item.instructions().strip()));
        }
        int seq = 0;
        for (Warning warning : warnings) {
            db.update("""
                    INSERT INTO contraindication_override(prescription_id, override_seq, medicine_id,
                        contra_type, related_medicine_id, contra_content, override_reason, recorded_at)
                    VALUES (:rx, :seq, :medicine, :type, :related, :content, :reason, :now)
                    """, map("rx", prescription, "seq", ++seq, "medicine", warning.medicine(), "type", warning.type(),
                    "related", warning.related(), "content", warning.content(),
                    "reason", reasons.get(warning.response().get("warningId")).strip(), "now", clock.instant()));
        }
        return Map.of("prescriptionId", prescription, "status", "ACTIVE", "warningCount", warnings.size());
    }

    @Transactional
    public Map<String, Object> cancel(HospitalPrincipal user, long prescriptionId) {
        user.doctor();
        long visitId = id(db.one("SELECT visit_id FROM prescription WHERE prescription_id = :id", Map.of("id", prescriptionId)), "visit_id");
        Map<String, Object> visit = clinical.doctorVisit(user, visitId, true);
        Map<String, Object> prescription = db.one("SELECT * FROM prescription WHERE prescription_id = :id FOR UPDATE", Map.of("id", prescriptionId));
        if (!"IN_PROGRESS".equals(visit.get("visit_status")) || !"ACTIVE".equals(prescription.get("prescription_status"))) {
            throw ApiException.conflict("INVALID_PRESCRIPTION_STATE", "취소할 수 없는 처방 상태입니다.");
        }
        db.update("UPDATE prescription SET prescription_status = 'CANCELLED' WHERE prescription_id = :id", Map.of("id", prescriptionId));
        return Map.of("prescriptionId", prescriptionId, "status", "CANCELLED");
    }

    private void validate(List<Long> ids) {
        if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size()
                || db.count("SELECT count(*) FROM medicine WHERE medicine_active AND medicine_id IN (:ids)", Map.of("ids", ids)) != ids.size()) {
            throw new ApiException(400, "INVALID_MEDICATIONS", "활성 약품을 중복 없이 선택하세요.");
        }
    }

    private Set<Long> currentMedications(long patient, LocalDate today) {
        return new TreeSet<>(db.rows("""
                SELECT DISTINCT pi.medicine_id FROM prescription pr
                JOIN prescription_item pi ON pi.prescription_id = pr.prescription_id
                JOIN visit v ON v.visit_id = pr.visit_id JOIN reservation r ON r.reservation_id = v.reservation_id
                WHERE r.patient_id = :patient AND pr.prescription_status = 'ACTIVE'
                  AND (pr.prescribed_at AT TIME ZONE 'Asia/Seoul')::date <= :today
                  AND (pr.prescribed_at AT TIME ZONE 'Asia/Seoul')::date + pi.total_days > :today
                """, map("patient", patient, "today", today)).stream().map(row -> id(row, "medicine_id")).toList());
    }

    private List<Warning> warnings(Map<String, Object> visit, SortedSet<Long> candidates, LocalDate today) {
        List<Warning> warnings = new ArrayList<>();
        int age = Period.between(date(visit, "birth_date"), today).getYears();
        long visitId = id(visit, "visit_id");
        for (var row : db.rows("SELECT * FROM age_contraindication WHERE medicine_id IN (:ids) ORDER BY medicine_id, age_seq", Map.of("ids", candidates))) {
            int limit = ((Number) row.get("age_limit")).intValue();
            if (age < limit || (age == limit && "LTE".equals(row.get("age_condition")))) {
                long medicine = id(row, "medicine_id");
                var key = map("medicationId", medicine, "ageSeq", id(row, "age_seq"));
                warnings.add(warning("AGE", visitId, today, key, List.of(medicine), text(row, "age_content"),
                        List.of(age, limit, row.get("age_condition")), medicine, null));
            }
        }
        TreeSet<Long> all = new TreeSet<>(candidates);
        all.addAll(currentMedications(id(visit, "patient_id"), today));
        for (var row : db.rows("""
                SELECT * FROM drug_interaction WHERE medicine_a_id IN (:ids) AND medicine_b_id IN (:ids)
                ORDER BY medicine_a_id, medicine_b_id
                """, Map.of("ids", all))) {
            long a = id(row, "medicine_a_id"), b = id(row, "medicine_b_id");
            if (!candidates.contains(a) && !candidates.contains(b)) { continue; }
            long prescribed = candidates.contains(a) ? a : b;
            warnings.add(warning("INTERACTION", visitId, today, map("medicationAId", a, "medicationBId", b),
                    List.of(a, b), text(row, "interaction_content"), List.of(), prescribed, prescribed == a ? b : a));
        }
        return warnings;
    }

    private Warning warning(String type, long visit, LocalDate date, Map<String, Object> key, List<Long> ids,
                            String content, List<Object> condition, long medicine, Long related) {
        try {
            byte[] context = mapper.writeValueAsBytes(map("type", type, "visit", visit, "date", date,
                    "key", key, "ids", ids, "content", content, "condition", condition, "medicine", medicine));
            String warningId = "w_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(context));
            return new Warning(map("warningId", warningId, "type", type, "ruleKey", key, "medicationIds", ids, "message", content),
                    medicine, related, type, content);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) { throw new IllegalStateException("경고 식별자를 생성할 수 없습니다.", e); }
    }

    private record Warning(Map<String, Object> response, long medicine, Long related, String type, String content) { }
}
