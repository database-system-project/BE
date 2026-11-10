package com.databasesystem.hospital.billing;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.databasesystem.hospital.common.Values.*;

@Service
@Transactional(readOnly = true)
public class BillingService {
    private static final String PAYMENTS = """
            SELECT p.*, f.fee_type FROM payment p JOIN consultation_fee f ON f.fee_id = p.fee_id
            """;
    private final Database db;
    private final ClinicalRepository clinical;
    private final Clock clock;

    public BillingService(Database db, ClinicalRepository clinical, Clock clock) {
        this.db = db; this.clinical = clinical; this.clock = clock;
    }

    public Map<String, Object> list(HospitalPrincipal user, Map<String, String> query) {
        user.require(Role.CASHIER);
        LocalDate from = Query.date(query, "from"), to = Query.date(query, "to");
        Query.range(from, to);
        if (from == null && to == null) { from = LocalDate.now(clock.withZone(SEOUL)); to = from; }
        String status = Query.choice(query, "paymentStatus", "UNPAID", "PAID");
        String sql = ClinicalRepository.VISITS + " WHERE v.visit_status = 'COMPLETED'";
        Map<String, Object> params = map();
        if (from != null) { sql += " AND v.completed_at >= :from"; params.put("from", from.atStartOfDay(SEOUL).toInstant()); }
        if (to != null) { sql += " AND v.completed_at < :until"; params.put("until", to.plusDays(1).atStartOfDay(SEOUL).toInstant()); }
        if (status != null) { sql += " AND " + (status.equals("UNPAID") ? "NOT " : "") + "EXISTS (SELECT 1 FROM payment p WHERE p.visit_id = v.visit_id)"; }
        Instant now = clock.instant();
        return Map.of("items", db.rows(sql + " ORDER BY v.completed_at, v.visit_id", params).stream().map(visit -> {
            Map<String, Object> result = quote(visit, now);
            result.putAll(map("patientId", id(visit, "patient_id"), "patientName", visit.get("patient_name"),
                    "doctorName", visit.get("doctor_name"), "departmentName", visit.get("department_name"), "completedAt", time(visit, "completed_at")));
            return result;
        }).toList());
    }

    public Map<String, Object> detail(HospitalPrincipal user, long visitId) {
        user.require(Role.CASHIER);
        Map<String, Object> visit = clinical.visit(visitId); completed(visit);
        return quote(visit, clock.instant());
    }

    @Transactional
    public Map<String, Object> pay(HospitalPrincipal user, long visitId, long requestedFee) {
        long staff = user.staff(); user.require(Role.CASHIER);
        Map<String, Object> visit = clinical.visit(visitId);
        db.lockPatient(id(visit, "patient_id"));
        db.one("SELECT visit_id FROM visit WHERE visit_id = :id FOR UPDATE", Map.of("id", visitId));
        visit = clinical.visit(visitId); completed(visit);
        var existing = payment(visitId);
        if (existing != null) { return paid(existing); }
        String type = feeType(visit);
        db.lockFee(type);
        Instant now = clock.instant();
        Map<String, Object> fee = fee(type, now);
        if (requestedFee != id(fee, "fee_id")) {
            throw new ApiException(409, "FEE_CHANGED", "변경된 진찰료를 확인하세요.",
                    map("feeId", id(fee, "fee_id"), "feeType", type, "amount", fee.get("fee_amount"), "currency", "KRW"));
        }
        db.insert("""
                INSERT INTO payment(visit_id, fee_id, paid_amount, paid_at, processed_by_staff_id)
                VALUES (:visit, :fee, :amount, :now, :staff) RETURNING payment_id
                """, map("visit", visitId, "fee", requestedFee, "amount", fee.get("fee_amount"), "now", now, "staff", staff));
        return paid(payment(visitId));
    }

    public Map<String, Object> receipt(HospitalPrincipal user, long paymentId) {
        user.require(Role.CASHIER, Role.PATIENT);
        Map<String, Object> payment = db.one(PAYMENTS + " WHERE p.payment_id = :id", Map.of("id", paymentId));
        Map<String, Object> visit = clinical.visit(id(payment, "visit_id"));
        if (user.role() == Role.PATIENT && user.patient() != id(visit, "patient_id")) { throw ApiException.notFound(); }
        Map<String, Object> result = paid(payment);
        result.putAll(map("receiptNumber", paymentId, "patientId", id(visit, "patient_id"), "patientName", visit.get("patient_name"),
                "visitDate", instant(visit, "completed_at").atZone(SEOUL).toLocalDate(),
                "departmentName", visit.get("department_name"), "doctorName", visit.get("doctor_name")));
        result.remove("paymentStatus"); result.remove("visitId");
        return result;
    }

    public Map<String, Object> print(HospitalPrincipal user, long visitId) {
        user.require(Role.CASHIER);
        Map<String, Object> visit = clinical.visit(visitId); completed(visit);
        Map<String, Object> payment = payment(visitId);
        if (payment == null) { throw ApiException.conflict("PAYMENT_REQUIRED", "결제를 먼저 완료하세요."); }
        List<Map<String, Object>> prescriptions = db.rows("""
                SELECT * FROM prescription WHERE visit_id = :id AND prescription_status = 'ACTIVE'
                ORDER BY prescribed_at, prescription_id
                """, Map.of("id", visitId)).stream().map(row -> map("prescriptionId", id(row, "prescription_id"),
                "visitId", visitId, "status", row.get("prescription_status"), "prescribedAt", time(row, "prescribed_at"),
                "items", db.rows("SELECT * FROM prescription_item WHERE prescription_id = :id ORDER BY medicine_id",
                        Map.of("id", id(row, "prescription_id"))).stream().map(Views::prescriptionItem).toList())).toList();
        return map("visitId", visitId, "paymentId", id(payment, "payment_id"), "patient", Views.patient(visit),
                "doctor", map("doctorId", id(visit, "doctor_id"), "name", visit.get("doctor_name"), "departmentName", visit.get("department_name")),
                "prescriptions", prescriptions, "canPrint", !prescriptions.isEmpty());
    }

    private void completed(Map<String, Object> visit) {
        if (!"COMPLETED".equals(visit.get("visit_status"))) { throw ApiException.conflict("VISIT_NOT_COMPLETED", "완료된 진료만 수납할 수 있습니다."); }
    }

    private Map<String, Object> payment(long visitId) {
        return db.optional(PAYMENTS + " WHERE p.visit_id = :id", Map.of("id", visitId)).orElse(null);
    }

    private Map<String, Object> paid(Map<String, Object> payment) {
        return map("paymentId", id(payment, "payment_id"), "visitId", id(payment, "visit_id"), "paymentStatus", "PAID",
                "feeId", id(payment, "fee_id"), "feeType", payment.get("fee_type"), "amount", payment.get("paid_amount"),
                "currency", "KRW", "paidAt", time(payment, "paid_at"));
    }

    private Map<String, Object> quote(Map<String, Object> visit, Instant now) {
        var payment = payment(id(visit, "visit_id"));
        if (payment != null) { return paid(payment); }
        String type = feeType(visit);
        Map<String, Object> fee = fee(type, now);
        return map("visitId", id(visit, "visit_id"), "paymentStatus", "UNPAID", "paymentId", null,
                "feeId", id(fee, "fee_id"), "feeType", type, "amount", fee.get("fee_amount"), "currency", "KRW");
    }

    private String feeType(Map<String, Object> visit) {
        long earlier = db.count("""
                SELECT count(*) FROM visit v JOIN reservation r ON r.reservation_id = v.reservation_id
                WHERE r.patient_id = :patient AND v.visit_status = 'COMPLETED'
                  AND (v.completed_at, v.visit_id) < (:completed, :id)
                """, map("patient", id(visit, "patient_id"), "completed", instant(visit, "completed_at"), "id", id(visit, "visit_id")));
        return earlier == 0 ? "FIRST_VISIT" : "FOLLOW_UP";
    }

    private Map<String, Object> fee(String type, Instant now) {
        return db.optional("""
                SELECT * FROM consultation_fee WHERE fee_type = :type AND effective_from <= :now
                ORDER BY effective_from DESC, fee_id DESC LIMIT 1
                """, map("type", type, "now", now)).orElseThrow(() -> ApiException.conflict("FEE_NOT_CONFIGURED", "적용할 진찰료가 설정되지 않았습니다."));
    }
}
