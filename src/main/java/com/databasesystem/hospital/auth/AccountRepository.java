package com.databasesystem.hospital.auth;

import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Values;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Repository
public class AccountRepository {
    private static final String ACCOUNT = """
            SELECT a.*, p.patient_id, s.staff_id, d.doctor_id,
                   CASE a.role
                       WHEN 'PATIENT' THEN p.patient_name
                       WHEN 'DOCTOR' THEN d.doctor_name
                       ELSE s.staff_name
                   END AS display_name, s.staff_active
              FROM user_account a
              LEFT JOIN patient p ON p.account_id = a.account_id AND a.role = 'PATIENT'
              LEFT JOIN staff s ON s.account_id = a.account_id AND a.role IN ('RECEPTION', 'CASHIER', 'ADMIN')
              LEFT JOIN doctor d ON d.account_id = a.account_id AND a.role = 'DOCTOR'
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
        if (!"ACTIVE".equals(row.get("account_status")) || row.get("display_name") == null) {
            return false;
        }
        return switch (Role.valueOf(Values.text(row, "role"))) {
            case PATIENT -> row.get("patient_id") != null;
            case DOCTOR -> row.get("doctor_id") != null;
            default -> row.get("staff_id") != null && Boolean.TRUE.equals(row.get("staff_active"));
        };
    }

    public long createManagedAccount(String loginId, String passwordHash, Role role, boolean active) {
        if (db.count("SELECT count(*) FROM user_account WHERE login_id = :login", Map.of("login", loginId)) > 0) {
            throw ApiException.conflict("LOGIN_ID_TAKEN", "이미 사용 중인 로그인 아이디입니다.");
        }
        return db.insert("""
                INSERT INTO user_account(login_id, password_hash, role, account_status)
                VALUES (:login, :password, :role, :status) RETURNING account_id
                """, Map.of("login", loginId, "password", passwordHash, "role", role.name(),
                "status", active ? "ACTIVE" : "INACTIVE"));
    }

    public HospitalPrincipal principal(Map<String, Object> row) {
        return new HospitalPrincipal(Values.id(row, "account_id"), Values.text(row, "login_id"),
                Values.text(row, "display_name"), Role.valueOf(Values.text(row, "role")),
                Values.nullableId(row, "patient_id"), Values.nullableId(row, "doctor_id"),
                Values.nullableId(row, "staff_id"), Values.id(row, "token_version"));
    }
}
