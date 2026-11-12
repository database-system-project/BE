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
public class AdminAgeContraindicationService {
    private static final String SELECT = """
            SELECT medicine_id, age_seq, age_limit, age_condition, age_content FROM age_contraindication
            """;
    private final Database db;

    public AdminAgeContraindicationService(Database db) { this.db = db; }

    /** 약품 행을 잠근 뒤 그 약품의 다음 ageSeq(MAX+1)를 발급한다. 같은 약품·나이·연산자 조합은 409다. */
    @Transactional
    public Map<String, Object> create(HospitalPrincipal user, AdminAgeContraindicationController.CreateRequest request) {
        user.require(Role.ADMIN);
        long medicationId = request.medicationId();
        int limit = request.ageLimit();
        String operator = request.ageOperator().name();
        String message = message(request.message());

        lockMedicine(medicationId);
        requireFree(medicationId, limit, operator, null);
        int seq = nextSeq(medicationId);
        db.update("""
                INSERT INTO age_contraindication(medicine_id, age_seq, age_limit, age_condition, age_content)
                VALUES (:medicine, :seq, :limit, :operator, :message)
                """, Values.map("medicine", medicationId, "seq", seq, "limit", limit, "operator", operator, "message", message));
        return view(medicationId, seq, limit, operator, message);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(HospitalPrincipal user, Map<String, String> query) {
        user.require(Role.ADMIN);
        Long medicationId = Query.id(query, "medicationId");
        StringBuilder sql = new StringBuilder(SELECT + " WHERE TRUE");
        Map<String, Object> parameters = new HashMap<>();
        if (medicationId != null) {
            sql.append(" AND medicine_id = :medication");
            parameters.put("medication", medicationId);
        }
        sql.append(" ORDER BY medicine_id, age_seq");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream().map(this::view).toList();
        return Map.of("items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(HospitalPrincipal user, long medicationId, long ageSeq) {
        user.require(Role.ADMIN);
        key(medicationId, ageSeq);
        return view(db.one(SELECT + " WHERE medicine_id = :medicine AND age_seq = :seq",
                Map.of("medicine", medicationId, "seq", ageSeq)));
    }

    /** 나이·연산자·내용 중 보낸 것만 바꾸고, 최종 조건으로 중복을 검사한다. 복합키는 바꿀 수 없다. */
    @Transactional
    public Map<String, Object> update(HospitalPrincipal user, long medicationId, long ageSeq, JsonNode body) {
        user.require(Role.ADMIN);
        key(medicationId, ageSeq);
        PatchBody patch = new PatchBody(body, Set.of("ageLimit", "ageOperator", "message"));
        Integer requestedLimit = patch.integer("ageLimit", 0, 32767);
        String requestedOperator = patch.text("ageOperator");
        if (requestedOperator != null && !requestedOperator.equals("LT") && !requestedOperator.equals("LTE")) {
            throw ApiException.badRequest();
        }
        String requestedMessage = patch.text("message") == null ? null : message(patch.text("message"));

        lockMedicine(medicationId);
        Map<String, Object> current = db.one(SELECT + " WHERE medicine_id = :medicine AND age_seq = :seq FOR UPDATE",
                Map.of("medicine", medicationId, "seq", ageSeq));
        int limit = requestedLimit == null ? ((Number) current.get("age_limit")).intValue() : requestedLimit;
        String operator = requestedOperator == null ? Values.text(current, "age_condition") : requestedOperator;
        String message = requestedMessage == null ? Values.text(current, "age_content") : requestedMessage;

        requireFree(medicationId, limit, operator, ageSeq);
        db.update("""
                UPDATE age_contraindication SET age_limit = :limit, age_condition = :operator, age_content = :message
                 WHERE medicine_id = :medicine AND age_seq = :seq
                """, Values.map("limit", limit, "operator", operator, "message", message,
                "medicine", medicationId, "seq", ageSeq));
        return view(medicationId, (int) ageSeq, limit, operator, message);
    }

    /** 처방 저장의 같은 약품 FOR SHARE 잠금과 연동한다. 없는 약품이면 404다. */
    private void lockMedicine(long medicationId) {
        db.one("SELECT medicine_id FROM medicine WHERE medicine_id = :id FOR UPDATE", Map.of("id", medicationId));
    }

    /** 같은 약품·나이·연산자 기준이 이미 있으면 409. (최종 차단은 uq_age_condition 23505) */
    private void requireFree(long medicationId, int limit, String operator, Long exceptSeq) {
        long taken = db.count("""
                SELECT count(*) FROM age_contraindication
                 WHERE medicine_id = :medicine AND age_limit = :limit AND age_condition = :operator
                   AND (CAST(:except AS BIGINT) IS NULL OR age_seq <> :except)
                """, Values.map("medicine", medicationId, "limit", limit, "operator", operator, "except", exceptSeq));
        if (taken > 0) {
            throw ApiException.conflict("DUPLICATE_DUR_RULE", "동일한 약품·연령 조건의 기준이 이미 있습니다.");
        }
    }

    private int nextSeq(long medicationId) {
        return ((Number) db.one("SELECT COALESCE(MAX(age_seq), 0) + 1 AS next FROM age_contraindication WHERE medicine_id = :id",
                Map.of("id", medicationId)).get("next")).intValue();
    }

    private static void key(long medicationId, long ageSeq) {
        if (medicationId <= 0 || ageSeq <= 0) { throw ApiException.badRequest(); }
    }

    private static String message(String value) {
        if (value == null || value.strip().isEmpty()) { throw ApiException.badRequest(); }
        return value.strip();
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return view(Values.id(row, "medicine_id"), ((Number) row.get("age_seq")).intValue(),
                ((Number) row.get("age_limit")).intValue(), Values.text(row, "age_condition"),
                Values.text(row, "age_content"));
    }

    private Map<String, Object> view(long medicationId, int ageSeq, int limit, String operator, String message) {
        return Values.map("type", "AGE", "medicationId", medicationId, "ageSeq", ageSeq, "ageLimit", limit,
                "ageOperator", operator, "message", message);
    }
}
