package com.databasesystem.hospital.patient;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Dates;
import com.databasesystem.hospital.common.Query;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.common.Views;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class PatientService {
    private final Database db;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public PatientService(Database db, PasswordEncoder passwords, Clock clock) {
        this.db = db;
        this.passwords = passwords;
        this.clock = clock;
    }

    public Map<String, Object> me(HospitalPrincipal principal) {
        Map<String, Object> profile;
        if (principal.role() == Role.PATIENT) {
            profile = Views.patient(patient(principal.patient()));
        } else if (principal.role() == Role.DOCTOR && principal.doctorId() != null) {
            var row = db.one("""
                    SELECT d.doctor_id, d.department_id, dep.department_name
                      FROM doctor d JOIN department dep ON dep.department_id = d.department_id
                     WHERE d.doctor_id = :id
                    """, Map.of("id", principal.doctorId()));
            profile = Values.map("doctorId", principal.doctorId(), "departmentId", row.get("department_id"),
                    "departmentName", row.get("department_name"));
        } else {
            profile = Values.map("staffId", principal.staffId(), "name", principal.name());
            if (principal.role() == Role.DOCTOR) { profile.put("doctorId", null); }
        }
        return Values.map("accountId", principal.accountId(), "loginId", principal.loginId(),
                "name", principal.name(), "role", principal.role().name(), "active", true, "profile", profile);
    }

    @Transactional
    public Map<String, Object> profile(HospitalPrincipal principal, Map<String, Object> request) {
        long id = principal.patient();
        patch(request, Set.of("name", "phone"));
        db.lockPatient(id);
        var old = patient(id);
        String name = request.containsKey("name") ? Values.clean(string(request, "name"), 50)
                : Values.text(old, "patient_name");
        String phone = request.containsKey("phone") ? Values.phone(string(request, "phone")) : Values.text(old, "phone");
        db.update("UPDATE patient SET patient_name = :name, phone = :phone WHERE patient_id = :id",
                Map.of("id", id, "name", name, "phone", phone));
        return Views.patient(patient(id));
    }

    @Transactional
    public Map<String, Object> password(HospitalPrincipal principal, PatientController.PasswordChange request) {
        var account = db.one("SELECT * FROM user_account WHERE account_id = :id FOR UPDATE",
                Map.of("id", principal.accountId()));
        if (Values.id(account, "token_version") != principal.tokenVersion()) {
            throw new ApiException(401, "UNAUTHORIZED", "다시 로그인하세요.");
        }
        if (!passwords.matches(request.currentPassword(), Values.text(account, "password_hash"))) {
            throw new ApiException(400, "INVALID_CURRENT_PASSWORD", "현재 비밀번호가 일치하지 않습니다.");
        }
        if (request.newPassword().equals(request.currentPassword())) { throw ApiException.badRequest(); }
        db.update("""
                UPDATE user_account SET password_hash = :password, token_version = token_version + 1
                 WHERE account_id = :id
                """, Map.of("id", principal.accountId(), "password", passwords.encode(request.newPassword())));
        return Map.of("changed", true);
    }

    public Map<String, Object> search(HospitalPrincipal principal, Map<String, String> query) {
        principal.require(Role.RECEPTION);
        if (query.isEmpty()) { throw ApiException.badRequest(); }
        StringBuilder sql = new StringBuilder("SELECT * FROM patient WHERE TRUE");
        Map<String, Object> parameters = Values.map();
        if (query.containsKey("name")) {
            parameters.put("name", Values.clean(query.get("name"), 50));
            sql.append(" AND position(lower(:name) in lower(patient_name)) > 0");
        }
        if (query.containsKey("birthDate")) {
            parameters.put("birth", Query.date(query, "birthDate"));
            sql.append(" AND birth_date = :birth");
        }
        if (query.containsKey("phone")) {
            parameters.put("phone", Values.phone(query.get("phone")));
            sql.append(" AND phone = :phone");
        }
        var items = new ArrayList<Map<String, Object>>();
        for (var row : db.rows(sql + " ORDER BY patient_id", parameters)) {
            String phone = Values.text(row, "phone");
            String masked = phone.length() > 7 ? phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4) : "****";
            items.add(Values.map("patientId", Values.id(row, "patient_id"), "name", row.get("patient_name"),
                    "birthDate", Values.date(row, "birth_date"), "gender", row.get("gender"), "displayPhone", masked));
        }
        return Map.of("items", items);
    }

    public Map<String, Object> detail(HospitalPrincipal principal, long patientId) {
        principal.require(Role.RECEPTION);
        return Views.patient(patient(patientId));
    }

    @Transactional
    public Map<String, Object> demographics(HospitalPrincipal principal, long patientId, Map<String, Object> request) {
        principal.require(Role.RECEPTION);
        patch(request, Set.of("birthDate", "gender"));
        db.lockPatient(patientId);
        var old = patient(patientId);
        LocalDate birth = Values.date(old, "birth_date");
        if (request.containsKey("birthDate")) {
            birth = Dates.parse(string(request, "birthDate"));
        }
        String gender = request.containsKey("gender") ? string(request, "gender") : Values.text(old, "gender");
        if (birth.isAfter(LocalDate.now(clock.withZone(Values.SEOUL))) || !Set.of("M", "F").contains(gender)) {
            throw ApiException.badRequest();
        }
        db.update("UPDATE patient SET birth_date = :birth, gender = :gender WHERE patient_id = :id",
                Map.of("id", patientId, "birth", birth, "gender", gender));
        db.update("""
                INSERT INTO patient_demographic_audit(patient_id, changed_by_staff_id, previous_birth_date,
                    new_birth_date, previous_gender, new_gender, changed_at)
                VALUES (:id, :staff, :previousBirth, :birth, :previousGender, :gender, :at)
                """, Values.map("id", patientId, "staff", principal.staff(), "previousBirth", Values.date(old, "birth_date"),
                "birth", birth, "previousGender", old.get("gender"), "gender", gender, "at", clock.instant()));
        return Views.patient(patient(patientId));
    }

    private Map<String, Object> patient(long id) {
        return db.one("SELECT * FROM patient WHERE patient_id = :id", Map.of("id", id));
    }

    private void patch(Map<String, Object> request, Set<String> fields) {
        if (request == null || request.isEmpty() || !fields.containsAll(request.keySet())
                || request.values().stream().anyMatch(value -> !(value instanceof String))) {
            throw ApiException.badRequest();
        }
    }

    private String string(Map<String, Object> request, String field) {
        if (!(request.get(field) instanceof String value)) { throw ApiException.badRequest(); }
        return value;
    }
}
