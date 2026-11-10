package com.databasesystem.hospital.common;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class ClinicalRepository {
    public static final String RESERVATIONS = """
            SELECT r.*, p.patient_name, p.birth_date, p.gender, p.phone, p.account_id AS patient_account_id,
                   ds.doctor_id, ds.schedule_date, ds.is_closed, d.doctor_name, d.doctor_active,
                   dep.department_id, dep.department_name, dep.department_active
              FROM reservation r
              JOIN patient p ON p.patient_id = r.patient_id
              JOIN doctor_schedule ds ON ds.schedule_id = r.schedule_id
              JOIN doctor d ON d.doctor_id = ds.doctor_id
              JOIN department dep ON dep.department_id = d.department_id
            """;
    public static final String VISITS = """
            SELECT v.*, r.patient_id, r.schedule_id, r.planned_reception_at, r.reservation_status,
                   p.patient_name, p.birth_date, p.gender, p.phone, p.account_id AS patient_account_id,
                   ds.doctor_id, ds.schedule_date, d.doctor_name, dep.department_id, dep.department_name
              FROM visit v
              JOIN reservation r ON r.reservation_id = v.reservation_id
              JOIN patient p ON p.patient_id = r.patient_id
              JOIN doctor_schedule ds ON ds.schedule_id = r.schedule_id
              JOIN doctor d ON d.doctor_id = ds.doctor_id
              JOIN department dep ON dep.department_id = d.department_id
            """;
    private final Database db;

    public ClinicalRepository(Database db) { this.db = db; }

    public Map<String, Object> visit(long visitId) {
        return db.one(VISITS + " WHERE v.visit_id = :id", Map.of("id", visitId));
    }

    public Map<String, Object> doctorVisit(HospitalPrincipal principal, long visitId, boolean writing) {
        long doctorId = principal.doctor();
        Map<String, Object> row = db.one(VISITS + " WHERE v.visit_id = :id AND ds.doctor_id = :doctor",
                Map.of("id", visitId, "doctor", doctorId));
        if (writing) {
            db.lockDoctor(doctorId);
            db.lockPatient(Values.id(row, "patient_id"));
            db.one("SELECT visit_id FROM visit WHERE visit_id = :id FOR UPDATE", Map.of("id", visitId));
            row = visit(visitId);
        }
        return row;
    }

    public Map<String, Object> ownedVisit(long patientId, long visitId) {
        return db.one(VISITS + " WHERE v.visit_id = :id AND r.patient_id = :patient",
                Map.of("id", visitId, "patient", patientId));
    }

    public void inProgress(Map<String, Object> row) {
        if (!"IN_PROGRESS".equals(row.get("visit_status"))) {
            throw ApiException.conflict("INVALID_VISIT_STATE", "진료 중인 담당 건에서만 처리할 수 있습니다.");
        }
    }
}
