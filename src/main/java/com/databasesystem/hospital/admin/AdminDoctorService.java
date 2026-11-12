package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Values;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminDoctorService {
    private static final String SELECT = """
            SELECT d.doctor_id, d.account_id, d.department_id, p.department_name, d.doctor_name, d.doctor_active
              FROM doctor d JOIN department p ON p.department_id = d.department_id
            """;
    private final Database db;

    public AdminDoctorService(Database db) { this.db = db; }

    /** DOCTOR 역할 직원 계정에 의사 프로필을 연결한다. 표시명은 staff.staff_name과 동기화한다. */
    @Transactional
    public Map<String, Object> create(HospitalPrincipal user, AdminDoctorController.CreateRequest request) {
        user.require(Role.ADMIN);
        long accountId = positive(request.accountId());
        long departmentId = positive(request.departmentId());
        String name = Values.clean(request.name(), 50);

        // 같은 계정을 동시에 두 번 연결하지 못하도록 계정 행을 잠근다.
        Map<String, Object> account = db.one("""
                SELECT role FROM user_account WHERE account_id = :id FOR UPDATE
                """, Map.of("id", accountId));
        db.one("SELECT department_id FROM department WHERE department_id = :id", Map.of("id", departmentId));
        if (!"DOCTOR".equals(account.get("role"))
                || db.count("SELECT count(*) FROM staff WHERE account_id = :id", Map.of("id", accountId)) == 0) {
            throw new ApiException(400, "INVALID_DOCTOR_ACCOUNT", "DOCTOR 역할 계정만 연결할 수 있습니다.");
        }
        if (db.count("SELECT count(*) FROM doctor WHERE account_id = :id", Map.of("id", accountId)) > 0) {
            throw ApiException.conflict("ACCOUNT_ALREADY_LINKED", "이미 의사에 연결된 계정입니다.");
        }
        long doctorId = db.insert("""
                INSERT INTO doctor(account_id, department_id, doctor_name, doctor_active)
                VALUES (:account, :department, :name, :active) RETURNING doctor_id
                """, Values.map("account", accountId, "department", departmentId, "name", name, "active", request.active()));
        db.update("UPDATE staff SET staff_name = :name WHERE account_id = :id", Values.map("name", name, "id", accountId));
        return view(db.one(SELECT + " WHERE d.doctor_id = :id", Map.of("id", doctorId)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(HospitalPrincipal user, Long departmentId, String keyword, Boolean active) {
        user.require(Role.ADMIN);
        if (departmentId != null) { positive(departmentId); }
        String word = keyword == null || keyword.strip().isEmpty() ? null : Values.clean(keyword, 50);
        StringBuilder sql = new StringBuilder(SELECT + " WHERE TRUE");
        Map<String, Object> parameters = new HashMap<>();
        if (departmentId != null) {
            sql.append(" AND d.department_id = :department");
            parameters.put("department", departmentId);
        }
        if (word != null) {
            sql.append(" AND position(lower(:keyword) in lower(d.doctor_name)) > 0");
            parameters.put("keyword", word);
        }
        if (active != null) {
            sql.append(" AND d.doctor_active = :active");
            parameters.put("active", active);
        }
        sql.append(" ORDER BY d.doctor_name, d.doctor_id");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream().map(this::view).toList();
        return Map.of("items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(HospitalPrincipal user, long doctorId) {
        user.require(Role.ADMIN);
        positive(doctorId);
        return view(db.one(SELECT + " WHERE d.doctor_id = :id", Map.of("id", doctorId)));
    }

    /** 보낸 필드만 수정한다. 표시명은 staff와 같은 트랜잭션에서 동기화하고, 기존 예약은 건드리지 않는다. */
    @Transactional
    public Map<String, Object> update(HospitalPrincipal user, long doctorId, JsonNode body) {
        user.require(Role.ADMIN);
        positive(doctorId);
        // accountId는 수정할 수 없으므로 허용 필드에 없다(보내면 400).
        PatchBody patch = new PatchBody(body, Set.of("departmentId", "name", "active"));
        Long requestedDepartment = patch.id("departmentId");
        String name = patch.text("name") == null ? null : Values.clean(patch.text("name"), 50);
        Boolean requestedActive = patch.bool("active");

        Map<String, Object> current = db.one("""
                SELECT account_id, department_id, doctor_name, doctor_active FROM doctor WHERE doctor_id = :id FOR UPDATE
                """, Map.of("id", doctorId));
        if (requestedDepartment != null) {
            db.one("SELECT department_id FROM department WHERE department_id = :id", Map.of("id", requestedDepartment));
        }
        long departmentId = requestedDepartment == null ? Values.id(current, "department_id") : requestedDepartment;
        String newName = name == null ? Values.text(current, "doctor_name") : name;
        boolean active = requestedActive == null ? Boolean.TRUE.equals(current.get("doctor_active")) : requestedActive;

        db.update("""
                UPDATE doctor SET department_id = :department, doctor_name = :name, doctor_active = :active
                 WHERE doctor_id = :id
                """, Values.map("department", departmentId, "name", newName, "active", active, "id", doctorId));
        if (name != null) {
            db.update("UPDATE staff SET staff_name = :name WHERE account_id = :account",
                    Values.map("name", name, "account", Values.id(current, "account_id")));
        }
        return view(db.one(SELECT + " WHERE d.doctor_id = :id", Map.of("id", doctorId)));
    }

    private static long positive(Long id) {
        if (id == null || id <= 0) { throw ApiException.badRequest(); }
        return id;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Values.map("doctorId", Values.id(row, "doctor_id"), "accountId", Values.id(row, "account_id"),
                "departmentId", Values.id(row, "department_id"), "departmentName", row.get("department_name"),
                "name", row.get("doctor_name"), "active", row.get("doctor_active"));
    }
}
