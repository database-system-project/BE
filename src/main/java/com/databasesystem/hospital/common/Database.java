package com.databasesystem.hospital.common;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class Database {
    private final NamedParameterJdbcTemplate jdbc;

    public Database(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> rows(String sql, Map<String, ?> parameters) {
        return jdbc.queryForList(sql, parameters(parameters));
    }

    public Optional<Map<String, Object>> optional(String sql, Map<String, ?> parameters) {
        List<Map<String, Object>> rows = rows(sql, parameters);
        if (rows.size() > 1) { throw new IllegalStateException("Expected at most one row"); }
        return rows.stream().findFirst();
    }

    public Map<String, Object> one(String sql, Map<String, ?> parameters) {
        return optional(sql, parameters).orElseThrow(ApiException::notFound);
    }

    public long insert(String sql, Map<String, ?> parameters) {
        Long id = jdbc.queryForObject(sql, parameters(parameters), Long.class);
        if (id == null) { throw new IllegalStateException("Missing generated identity"); }
        return id;
    }

    public int update(String sql, Map<String, ?> parameters) {
        return jdbc.update(sql, parameters(parameters));
    }

    public long count(String sql, Map<String, ?> parameters) {
        Long count = jdbc.queryForObject(sql, parameters(parameters), Long.class);
        return count == null ? 0 : count;
    }

    /** 약품번호들의 제품명을 한 번에 조회한다. 없는 번호는 결과에 포함하지 않는다. */
    public Map<Long, String> medicineNames(Collection<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids.isEmpty()) { return names; }
        for (Map<String, Object> row : rows(
                "SELECT medicine_id, product_name FROM medicine WHERE medicine_id IN (:ids)", Map.of("ids", ids))) {
            names.put(((Number) row.get("medicine_id")).longValue(), (String) row.get("product_name"));
        }
        return names;
    }

    public void lockPatient(long patientId) {
        one("SELECT patient_id FROM patient WHERE patient_id = :id FOR UPDATE", Map.of("id", patientId));
    }

    public void lockDoctor(long doctorId) {
        one("SELECT doctor_id FROM doctor WHERE doctor_id = :id FOR UPDATE", Map.of("id", doctorId));
    }

    public void lockFee(String feeType) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(8001, :type)",
                Map.of("type", "FIRST_VISIT".equals(feeType) ? 1 : 2));
    }

    private Map<String, Object> parameters(Map<String, ?> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key,
                value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value));
        return result;
    }
}
