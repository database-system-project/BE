package com.databasesystem.hospital.history;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

import static com.databasesystem.hospital.common.Values.*;

@Service
@Transactional(readOnly = true)
public class HistoryService {
    private final Database db;
    private final ClinicalRepository clinical;
    public HistoryService(Database db, ClinicalRepository clinical) { this.db = db; this.clinical = clinical; }

    public Map<String, Object> list(HospitalPrincipal user, Map<String, String> query) {
        long patient = user.patient();
        LocalDate from = Query.date(query, "from"), to = Query.date(query, "to"); Query.range(from, to);
        String sql = ClinicalRepository.VISITS + " WHERE r.patient_id = :patient AND v.visit_status = 'COMPLETED'";
        Map<String, Object> params = map("patient", patient);
        if (from != null) { sql += " AND v.completed_at >= :from"; params.put("from", from.atStartOfDay(SEOUL).toInstant()); }
        if (to != null) { sql += " AND v.completed_at < :until"; params.put("until", to.plusDays(1).atStartOfDay(SEOUL).toInstant()); }
        return Map.of("items", db.rows(sql + " ORDER BY v.completed_at DESC, v.visit_id DESC", params).stream().map(visit -> {
            Map<String, Object> result = map("visitId", id(visit, "visit_id"),
                    "visitDate", instant(visit, "completed_at").atZone(SEOUL).toLocalDate(), "departmentName", visit.get("department_name"),
                    "doctorName", visit.get("doctor_name"), "status", visit.get("visit_status"));
            result.putAll(payment(id(visit, "visit_id")));
            return result;
        }).toList());
    }

    public Map<String, Object> detail(HospitalPrincipal user, long visitId) {
        Map<String, Object> visit = ownedCompleted(user, visitId);
        Map<String, Object> result = map("visit", Views.visit(visit), "medicalRecord",
                db.optional("SELECT * FROM medical_record WHERE visit_id = :id", Map.of("id", visitId)).map(Views::record).orElse(null),
                "prescriptions", db.rows("SELECT * FROM prescription WHERE visit_id = :id ORDER BY prescribed_at, prescription_id",
                        Map.of("id", visitId)).stream().map(this::summary).toList());
        result.putAll(payment(visitId));
        return result;
    }

    public Map<String, Object> prescription(HospitalPrincipal user, long prescriptionId) {
        user.patient();
        Map<String, Object> prescription = db.one("SELECT * FROM prescription WHERE prescription_id = :id", Map.of("id", prescriptionId));
        ownedCompleted(user, id(prescription, "visit_id"));
        Map<String, Object> result = summary(prescription);
        result.put("items", db.rows("""
                SELECT pi.*, m.product_name, m.efficacy, m.usage_text, m.precautions, m.storage
                FROM prescription_item pi JOIN medicine m ON m.medicine_id = pi.medicine_id
                WHERE pi.prescription_id = :id ORDER BY pi.medicine_id
                """, Map.of("id", prescriptionId)).stream().map(item -> {
            Map<String, Object> output = Views.prescriptionItem(item);
            output.putAll(map("name", item.get("product_name"), "efficacy", item.get("efficacy"), "usage", item.get("usage_text"),
                    "precautions", item.get("precautions"), "storage", item.get("storage")));
            return output;
        }).toList());
        return result;
    }

    private Map<String, Object> ownedCompleted(HospitalPrincipal user, long visitId) {
        Map<String, Object> visit = clinical.ownedVisit(user.patient(), visitId);
        if (!"COMPLETED".equals(visit.get("visit_status"))) { throw ApiException.notFound(); }
        return visit;
    }

    private Map<String, Object> summary(Map<String, Object> prescription) {
        return map("prescriptionId", id(prescription, "prescription_id"), "status", prescription.get("prescription_status"),
                "prescribedAt", time(prescription, "prescribed_at"));
    }

    private Map<String, Object> payment(long visitId) {
        Long id = db.optional("SELECT payment_id FROM payment WHERE visit_id = :id", Map.of("id", visitId))
                .map(row -> id(row, "payment_id")).orElse(null);
        return map("paymentStatus", id == null ? "UNPAID" : "PAID", "paymentId", id);
    }
}
