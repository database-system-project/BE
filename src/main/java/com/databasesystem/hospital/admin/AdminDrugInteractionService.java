package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Query;
import com.databasesystem.hospital.common.Values;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminDrugInteractionService {
    private static final String SELECT = "SELECT medicine_a_id, medicine_b_id, interaction_content FROM drug_interaction";
    private final Database db;

    public AdminDrugInteractionService(Database db) { this.db = db; }

    /** 두 ID를 오름차순으로 정규화하므로 역순으로 보내도 같은 조합이다. 같은 약품끼리는 400이다. */
    @Transactional
    public Map<String, Object> create(HospitalPrincipal user, AdminDrugInteractionController.CreateRequest request) {
        user.require(Role.ADMIN);
        long low = Math.min(request.medicationAId(), request.medicationBId());
        long high = Math.max(request.medicationAId(), request.medicationBId());
        if (low == high) { throw ApiException.badRequest(); }
        String message = message(request.message());

        lockMedicines(low, high);
        if (db.count("SELECT count(*) FROM drug_interaction WHERE medicine_a_id = :a AND medicine_b_id = :b",
                Map.of("a", low, "b", high)) > 0) {
            throw ApiException.conflict("DUPLICATE_DUR_RULE", "해당 약품 쌍의 병용금기 기준이 이미 있습니다.");
        }
        // 약품 행 잠금으로 직렬화되며, 그래도 겹치면 복합 PK(23505)가 DUPLICATE_DUR_RULE로 변환된다.
        db.update("INSERT INTO drug_interaction(medicine_a_id, medicine_b_id, interaction_content) VALUES (:a, :b, :message)",
                Values.map("a", low, "b", high, "message", message));
        return view(low, high, message);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(HospitalPrincipal user, Map<String, String> query) {
        user.require(Role.ADMIN);
        Long medicationId = Query.id(query, "medicationId");
        StringBuilder sql = new StringBuilder(SELECT + " WHERE TRUE");
        Map<String, Object> parameters = new HashMap<>();
        if (medicationId != null) {
            sql.append(" AND (medicine_a_id = :medication OR medicine_b_id = :medication)");
            parameters.put("medication", medicationId);
        }
        sql.append(" ORDER BY medicine_a_id, medicine_b_id");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream()
                .map(row -> view(Values.id(row, "medicine_a_id"), Values.id(row, "medicine_b_id"),
                        Values.text(row, "interaction_content"))).toList();
        return Map.of("items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(HospitalPrincipal user, long medicationAId, long medicationBId) {
        user.require(Role.ADMIN);
        long low = Math.min(medicationAId, medicationBId);
        long high = Math.max(medicationAId, medicationBId);
        pair(low, high);
        Map<String, Object> row = db.one(SELECT + " WHERE medicine_a_id = :a AND medicine_b_id = :b", Map.of("a", low, "b", high));
        return view(low, high, Values.text(row, "interaction_content"));
    }

    /** 수정할 수 있는 것은 message뿐이다. 약품 ID·유형 등을 본문에 보내면 400이다. */
    @Transactional
    public Map<String, Object> update(HospitalPrincipal user, long medicationAId, long medicationBId, JsonNode body) {
        user.require(Role.ADMIN);
        long low = Math.min(medicationAId, medicationBId);
        long high = Math.max(medicationAId, medicationBId);
        pair(low, high);
        String message = message(new PatchBody(body, Set.of("message")).text("message"));

        lockMedicines(low, high);
        db.one(SELECT + " WHERE medicine_a_id = :a AND medicine_b_id = :b FOR UPDATE", Map.of("a", low, "b", high));
        db.update("UPDATE drug_interaction SET interaction_content = :message WHERE medicine_a_id = :a AND medicine_b_id = :b",
                Values.map("message", message, "a", low, "b", high));
        return view(low, high, message);
    }

    /** 두 약품 행을 ID 오름차순으로 잠근다. 하나라도 없으면 404다. (처방 저장의 FOR SHARE 잠금과 연동) */
    private void lockMedicines(long low, long high) {
        List<Map<String, Object>> locked = db.rows(
                "SELECT medicine_id FROM medicine WHERE medicine_id IN (:low, :high) ORDER BY medicine_id FOR UPDATE",
                Map.of("low", low, "high", high));
        if (locked.size() != 2) { throw ApiException.notFound(); }
    }

    private static void pair(long low, long high) {
        if (low <= 0 || low == high) { throw ApiException.badRequest(); }
    }

    private static String message(String value) {
        if (value == null || value.strip().isEmpty()) { throw ApiException.badRequest(); }
        return value.strip();
    }

    private Map<String, Object> view(long low, long high, String message) {
        return Values.map("type", "INTERACTION", "medicationAId", low, "medicationBId", high, "message", message);
    }
}
