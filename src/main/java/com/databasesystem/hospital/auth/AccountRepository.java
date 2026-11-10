package com.databasesystem.hospital.auth;

import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Values;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class AccountRepository {
    private static final String ACCOUNT = """
            SELECT a.*, p.patient_id, s.staff_id, d.doctor_id,
                   COALESCE(p.patient_name, s.staff_name) AS display_name,
                   COALESCE(s.staff_active, TRUE) AS staff_active
              FROM user_account a
              LEFT JOIN patient p ON p.account_id = a.account_id
              LEFT JOIN staff s ON s.account_id = a.account_id
              LEFT JOIN doctor d ON d.account_id = a.account_id
            """;
    private final Database db;

    public AccountRepository(Database db) { this.db = db; }

    public Optional<Map<String, Object>> byLogin(String loginId) {
        return db.optional(ACCOUNT + " WHERE a.login_id = :login", Map.of("login", loginId));
    }

    public Optional<HospitalPrincipal> active(long accountId) {
        return db.optional(ACCOUNT + " WHERE a.account_id = :id", Map.of("id", accountId))
                .filter(this::isActive).map(this::principal);
    }

    public boolean isActive(Map<String, Object> row) {
        return "ACTIVE".equals(row.get("account_status")) && Boolean.TRUE.equals(row.get("staff_active"))
                && row.get("display_name") != null
                && (!"PATIENT".equals(row.get("role")) || row.get("patient_id") != null)
                && ("PATIENT".equals(row.get("role")) || row.get("staff_id") != null);
    }

    public HospitalPrincipal principal(Map<String, Object> row) {
        return new HospitalPrincipal(Values.id(row, "account_id"), Values.text(row, "login_id"),
                Values.text(row, "display_name"), Role.valueOf(Values.text(row, "role")),
                Values.nullableId(row, "patient_id"), Values.nullableId(row, "doctor_id"),
                Values.nullableId(row, "staff_id"), Values.id(row, "token_version"));
    }
}
