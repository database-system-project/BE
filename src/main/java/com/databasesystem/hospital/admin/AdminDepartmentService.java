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
public class AdminDepartmentService {
    private static final String SELECT = "SELECT department_id, department_name, department_active FROM department";
    private final Database db;

    public AdminDepartmentService(Database db) { this.db = db; }

    @Transactional
    public Map<String, Object> create(HospitalPrincipal user, AdminDepartmentController.CreateRequest request) {
        user.require(Role.ADMIN);
        String name = Values.clean(request.name(), 50);
        boolean active = request.active();
        requireNameFree(name, null);
        // 동시 요청은 uq_department_name(23505 → DUPLICATE_DEPARTMENT_NAME)이 최종 차단한다.
        long id = db.insert("""
                INSERT INTO department(department_name, department_active) VALUES (:name, :active)
                RETURNING department_id
                """, Values.map("name", name, "active", active));
        return view(id, name, active);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(HospitalPrincipal user, String keyword, Boolean active) {
        user.require(Role.ADMIN);
        String word = keyword == null || keyword.strip().isEmpty() ? null : Values.clean(keyword, 50);
        StringBuilder sql = new StringBuilder(SELECT + " WHERE TRUE");
        Map<String, Object> parameters = new HashMap<>();
        if (word != null) {
            sql.append(" AND position(lower(:keyword) in lower(department_name)) > 0");
            parameters.put("keyword", word);
        }
        if (active != null) {
            sql.append(" AND department_active = :active");
            parameters.put("active", active);
        }
        sql.append(" ORDER BY department_name, department_id");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream().map(this::view).toList();
        return Map.of("items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(HospitalPrincipal user, long departmentId) {
        user.require(Role.ADMIN);
        if (departmentId <= 0) { throw ApiException.badRequest(); }
        return view(db.one(SELECT + " WHERE department_id = :id", Map.of("id", departmentId)));
    }

    @Transactional
    public Map<String, Object> update(HospitalPrincipal user, long departmentId, JsonNode body) {
        user.require(Role.ADMIN);
        if (departmentId <= 0) { throw ApiException.badRequest(); }
        PatchBody patch = new PatchBody(body, Set.of("name", "active"));
        String name = patch.text("name") == null ? null : Values.clean(patch.text("name"), 50);
        Boolean requestedActive = patch.bool("active");

        Map<String, Object> current = db.one(SELECT + " WHERE department_id = :id FOR UPDATE", Map.of("id", departmentId));
        if (name != null) { requireNameFree(name, departmentId); }
        String newName = name == null ? Values.text(current, "department_name") : name;
        boolean newActive = requestedActive == null ? Boolean.TRUE.equals(current.get("department_active")) : requestedActive;
        db.update("""
                UPDATE department SET department_name = :name, department_active = :active WHERE department_id = :id
                """, Values.map("name", newName, "active", newActive, "id", departmentId));
        return view(departmentId, newName, newActive);
    }

    /** 양끝 공백 제거·대소문자 무시 기준(uq_department_name과 같은 정규화)으로 중복을 확인한다. 비활성 진료과도 포함. */
    private void requireNameFree(String name, Long exceptId) {
        long taken = db.count("""
                SELECT count(*) FROM department
                 WHERE lower(btrim(department_name)) = lower(btrim(:name))
                   AND (CAST(:except AS BIGINT) IS NULL OR department_id <> :except)
                """, Values.map("name", name, "except", exceptId));
        if (taken > 0) {
            throw ApiException.conflict("DUPLICATE_DEPARTMENT_NAME", "같은 이름의 진료과가 이미 있습니다.");
        }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return view(Values.id(row, "department_id"), Values.text(row, "department_name"),
                Boolean.TRUE.equals(row.get("department_active")));
    }

    private Map<String, Object> view(long id, String name, boolean active) {
        return Values.map("departmentId", id, "name", name, "active", active);
    }
}
