package com.databasesystem.hospital.visit;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.ClinicalRepository;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Query;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.common.Views;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class VisitService {
    private final Database db;
    private final ClinicalRepository clinical;
    private final Clock clock;

    public VisitService(Database db, ClinicalRepository clinical, Clock clock) {
        this.db = db; this.clinical = clinical; this.clock = clock;
    }

    public Map<String, Object> appointments(HospitalPrincipal user, Map<String, String> query) {
        user.require(Role.RECEPTION);
        LocalDate date = Query.date(query, "date");
        if (date != null && !date.equals(today())) { throw ApiException.badRequest(); }
        String status = Query.choice(query, "status", "RESERVED", "CHECKED_IN", "CANCELLED");
        StringBuilder sql = new StringBuilder(ClinicalRepository.RESERVATIONS
                + " LEFT JOIN visit v ON v.reservation_id = r.reservation_id WHERE ds.schedule_date = :date");
        Map<String, Object> parameters = Values.map("date", today());
        if (status != null) { sql.append(" AND r.reservation_status = :status"); parameters.put("status", status); }
        if (query.containsKey("keyword")) {
            parameters.put("keyword", Values.clean(query.get("keyword"), 50));
            sql.append(" AND (position(:keyword in p.patient_name) > 0 OR cast(p.patient_id as text) = :keyword)");
        }
        String querySql = sql.toString().replace("SELECT r.*,", "SELECT v.visit_id, r.*,");
        return Map.of("items", db.rows(querySql + " ORDER BY r.planned_reception_at, r.reservation_id", parameters)
                .stream().map(this::appointmentView).toList());
    }

    @Transactional
    public Map<String, Object> checkIn(HospitalPrincipal user, long id) {
        user.require(Role.RECEPTION);
        var reservation = reservation(id);
        db.lockDoctor(Values.id(reservation, "doctor_id"));
        lockSchedule(Values.id(reservation, "schedule_id"));
        db.one("SELECT reservation_id FROM reservation WHERE reservation_id = :id FOR UPDATE", Map.of("id", id));
        reservation = reservation(id);
        if (db.count("SELECT count(*) FROM visit WHERE reservation_id = :id", Map.of("id", id)) > 0) {
            throw ApiException.conflict("ALREADY_CHECKED_IN", "이미 접수한 예약입니다.");
        }
        if (!today().equals(Values.date(reservation, "schedule_date"))
                || !"RESERVED".equals(reservation.get("reservation_status"))
                || !LocalTime.now(clock.withZone(Values.SEOUL)).isBefore(LocalTime.of(17, 0))) {
            throw ApiException.conflict("INVALID_APPOINTMENT_STATE", "당일 마감 전 예약만 접수할 수 있습니다.");
        }
        db.update("UPDATE reservation SET reservation_status = 'CHECKED_IN' WHERE reservation_id = :id", Map.of("id", id));
        long visit = db.insert("""
                INSERT INTO visit(reservation_id, checked_in_at, checked_in_by_account_id)
                VALUES (:id, :at, :account) RETURNING visit_id
                """, Values.map("id", id, "at", clock.instant(), "account", user.accountId()));
        return Values.map("appointmentId", id, "appointmentStatus", "CHECKED_IN", "visit", Views.visit(clinical.visit(visit)));
    }

    public Map<String, Object> queue(HospitalPrincipal user, Map<String, String> query) {
        user.require(Role.RECEPTION, Role.DOCTOR);
        Long doctor = Query.id(query, "doctorId");
        if (user.role() == Role.DOCTOR) {
            long own = user.doctor();
            if (doctor != null && doctor != own) { throw ApiException.forbidden(); }
            doctor = own;
        }
        String status = Query.choice(query, "status", "WAITING", "IN_PROGRESS", "COMPLETED", "CANCELLED");
        StringBuilder sql = new StringBuilder(ClinicalRepository.VISITS + " WHERE ds.schedule_date = :date");
        Map<String, Object> parameters = Values.map("date", today());
        if (doctor != null) { sql.append(" AND ds.doctor_id = :doctor"); parameters.put("doctor", doctor); }
        if (status == null) { sql.append(" AND v.visit_status IN ('WAITING', 'IN_PROGRESS')"); }
        else { sql.append(" AND v.visit_status = :status"); parameters.put("status", status); }
        return Map.of("items", db.rows(sql + " ORDER BY v.checked_in_at, v.visit_id", parameters).stream().map(Views::visit).toList());
    }

    @Transactional
    public Map<String, Object> cancel(HospitalPrincipal user, long id, String reason) {
        user.require(Role.RECEPTION);
        reason = Values.clean(reason, 200);
        var visit = clinical.visit(id);
        db.lockDoctor(Values.id(visit, "doctor_id"));
        lockSchedule(Values.id(visit, "schedule_id"));
        long reservation = Values.id(visit, "reservation_id");
        db.one("SELECT reservation_id FROM reservation WHERE reservation_id = :id FOR UPDATE", Map.of("id", reservation));
        db.one("SELECT visit_id FROM visit WHERE visit_id = :id FOR UPDATE", Map.of("id", id));
        visit = clinical.visit(id);
        if (!today().equals(Values.date(visit, "schedule_date")) || !"WAITING".equals(visit.get("visit_status"))) {
            throw state();
        }
        db.update("""
                UPDATE visit SET visit_status = 'CANCELLED', visit_cancelled_at = :at,
                    cancelled_by_account_id = :account, cancel_reason = :reason WHERE visit_id = :id
                """, Values.map("id", id, "at", clock.instant(), "account", user.accountId(), "reason", reason));
        db.update("""
                UPDATE reservation SET reservation_status = 'CANCELLED', reservation_cancelled_at = :at
                 WHERE reservation_id = :id
                """, Values.map("id", reservation, "at", clock.instant()));
        return Values.map("visitId", id, "status", "CANCELLED", "appointmentId", reservation,
                "appointmentStatus", "CANCELLED", "cancelledAt", clock.instant().atZone(Values.SEOUL).toOffsetDateTime());
    }

    public Map<String, Object> detail(HospitalPrincipal user, long id) {
        var row = clinical.doctorVisit(user, id, false);
        Object record = db.optional("SELECT * FROM medical_record WHERE visit_id = :id", Map.of("id", id)).map(Views::record).orElse(null);
        var medications = db.rows("""
                SELECT rx.prescription_id, rx.prescribed_at, pi.medicine_id, pi.total_days, m.product_name
                  FROM prescription rx JOIN prescription_item pi ON pi.prescription_id = rx.prescription_id
                  JOIN medicine m ON m.medicine_id = pi.medicine_id
                  JOIN visit v ON v.visit_id = rx.visit_id JOIN reservation r ON r.reservation_id = v.reservation_id
                 WHERE r.patient_id = :patient AND v.visit_id <> :id AND rx.prescription_status = 'ACTIVE'
                   AND (rx.prescribed_at AT TIME ZONE 'Asia/Seoul')::date <= :today
                   AND :today < (rx.prescribed_at AT TIME ZONE 'Asia/Seoul')::date + pi.total_days
                 ORDER BY rx.prescribed_at, rx.prescription_id, pi.medicine_id
                """, Map.of("patient", Values.id(row, "patient_id"), "id", id, "today", today())).stream()
                .map(m -> Values.map("prescriptionId", Values.id(m, "prescription_id"), "medicationId", Values.id(m, "medicine_id"),
                        "medicationName", Values.text(m, "product_name"),
                        "prescribedAt", Values.time(m, "prescribed_at"), "durationDays", m.get("total_days"))).toList();
        var prescriptions = db.rows("SELECT * FROM prescription WHERE visit_id = :id ORDER BY prescribed_at, prescription_id",
                Map.of("id", id)).stream().map(rx -> Values.map("prescriptionId", Values.id(rx, "prescription_id"),
                "visitId", id, "status", rx.get("prescription_status"), "prescribedAt", Values.time(rx, "prescribed_at"),
                "items", db.rows("""
                        SELECT pi.*, m.product_name FROM prescription_item pi
                          JOIN medicine m ON m.medicine_id = pi.medicine_id
                         WHERE pi.prescription_id = :id ORDER BY pi.medicine_id
                        """, Map.of("id", Values.id(rx, "prescription_id"))).stream().map(Views::prescriptionItemWithName).toList())).toList();
        return Values.map("visit", Views.visit(row), "patient", Views.patient(row), "medicalRecord", record,
                "currentMedications", medications, "prescriptions", prescriptions);
    }

    @Transactional
    public Map<String, Object> start(HospitalPrincipal user, long id) {
        var row = clinical.doctorVisit(user, id, true);
        if (!today().equals(Values.date(row, "schedule_date")) || !"WAITING".equals(row.get("visit_status"))) { throw state(); }
        if (db.count("""
                SELECT count(*) FROM visit v JOIN reservation r ON r.reservation_id = v.reservation_id
                JOIN doctor_schedule ds ON ds.schedule_id = r.schedule_id
                WHERE ds.doctor_id = :doctor AND v.visit_status = 'IN_PROGRESS'
                """, Map.of("doctor", user.doctor())) > 0) {
            throw ApiException.conflict("DOCTOR_ALREADY_IN_PROGRESS", "이미 진행 중인 진료가 있습니다.");
        }
        var head = db.one(ClinicalRepository.VISITS + " WHERE ds.doctor_id = :doctor AND ds.schedule_date = :date"
                        + " AND v.visit_status = 'WAITING' ORDER BY v.checked_in_at, v.visit_id LIMIT 1",
                Map.of("doctor", user.doctor(), "date", today()));
        if (Values.id(head, "visit_id") != id) { throw ApiException.conflict("NOT_QUEUE_HEAD", "먼저 접수한 환자가 있습니다."); }
        db.update("UPDATE visit SET visit_status = 'IN_PROGRESS', started_at = :at WHERE visit_id = :id",
                Values.map("id", id, "at", clock.instant()));
        return Values.map("visitId", id, "status", "IN_PROGRESS", "startedAt", clock.instant().atZone(Values.SEOUL).toOffsetDateTime());
    }

    @Transactional
    public Map<String, Object> createRecord(HospitalPrincipal user, long id, VisitController.MedicalRecord request) {
        clinical.inProgress(clinical.doctorVisit(user, id, true));
        db.insert("""
                INSERT INTO medical_record(visit_id, symptoms, diagnosis, treatment_note, written_at, record_updated_at)
                VALUES (:id, :symptoms, :diagnosis, :note, :at, :at) RETURNING record_id
                """, Values.map("id", id, "symptoms", request.symptoms().strip(), "diagnosis", request.diagnosis().strip(),
                "note", request.treatmentNote().strip(), "at", clock.instant()));
        return record(id);
    }

    @Transactional
    public Map<String, Object> updateRecord(HospitalPrincipal user, long id, Map<String, Object> request) {
        clinical.inProgress(clinical.doctorVisit(user, id, true));
        Set<String> fields = Set.of("symptoms", "diagnosis", "treatmentNote");
        if (request == null || request.isEmpty() || !fields.containsAll(request.keySet())
                || request.values().stream().anyMatch(value -> !(value instanceof String string) || string.isBlank())) {
            throw ApiException.badRequest();
        }
        var old = db.one("SELECT * FROM medical_record WHERE visit_id = :id", Map.of("id", id));
        db.update("""
                UPDATE medical_record SET symptoms = :symptoms, diagnosis = :diagnosis,
                    treatment_note = :note, record_updated_at = :at WHERE visit_id = :id
                """, Values.map("id", id, "symptoms", request.getOrDefault("symptoms", old.get("symptoms")),
                "diagnosis", request.getOrDefault("diagnosis", old.get("diagnosis")),
                "note", request.getOrDefault("treatmentNote", old.get("treatment_note")), "at", clock.instant()));
        return record(id);
    }

    @Transactional
    public Map<String, Object> complete(HospitalPrincipal user, long id) {
        clinical.inProgress(clinical.doctorVisit(user, id, true));
        if (db.count("SELECT count(*) FROM medical_record WHERE visit_id = :id", Map.of("id", id)) == 0) {
            throw ApiException.conflict("MEDICAL_RECORD_REQUIRED", "진료기록을 저장한 뒤 완료하세요.");
        }
        db.update("UPDATE visit SET visit_status = 'COMPLETED', completed_at = :at WHERE visit_id = :id",
                Values.map("id", id, "at", clock.instant()));
        return Values.map("visitId", id, "status", "COMPLETED", "completedAt", clock.instant().atZone(Values.SEOUL).toOffsetDateTime(),
                "paymentStatus", "UNPAID");
    }

    private Map<String, Object> reservation(long id) {
        return db.one(ClinicalRepository.RESERVATIONS + " WHERE r.reservation_id = :id", Map.of("id", id));
    }

    private void lockSchedule(long id) { db.one("SELECT schedule_id FROM doctor_schedule WHERE schedule_id = :id FOR UPDATE", Map.of("id", id)); }
    private LocalDate today() { return LocalDate.now(clock.withZone(Values.SEOUL)); }
    private ApiException state() { return ApiException.conflict("INVALID_VISIT_STATE", "현재 진료 상태에서는 처리할 수 없습니다."); }
    private Map<String, Object> record(long id) { return Views.record(db.one("SELECT * FROM medical_record WHERE visit_id = :id", Map.of("id", id))); }

    private Map<String, Object> appointmentView(Map<String, Object> row) {
        long id = Values.id(row, "reservation_id");
        boolean change = "RESERVED".equals(row.get("reservation_status")) && !Values.instant(row, "planned_reception_at").isBefore(clock.instant());
        return Values.map("appointmentId", id, "appointmentCode", "R%06d".formatted(id), "patientId", Values.id(row, "patient_id"),
                "patientName", row.get("patient_name"), "departmentId", Values.id(row, "department_id"), "departmentName", row.get("department_name"),
                "doctorId", Values.id(row, "doctor_id"), "doctorName", row.get("doctor_name"), "scheduleId", Values.id(row, "schedule_id"),
                "startsAt", Values.time(row, "planned_reception_at"), "status", row.get("reservation_status"),
                "canReschedule", change, "canCancel", change, "visitId", Values.nullableId(row, "visit_id"));
    }
}
