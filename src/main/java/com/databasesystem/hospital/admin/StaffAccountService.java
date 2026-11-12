package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Values;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StaffAccountService {
    private final Database db;
    private final PasswordEncoder passwords;

    public StaffAccountService(Database db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    /** 직원 계정과 staff 프로필을 한 트랜잭션으로 만든다. DOCTOR의 doctor 프로필은 의사 등록 API에서 연결한다. */
    @Transactional
    public Map<String, Object> create(HospitalPrincipal user, StaffAccountController.CreateRequest request) {
        user.require(Role.ADMIN);
        if (request.role() == Role.PATIENT) { throw ApiException.badRequest(); }
        String loginId = Values.clean(request.loginId(), 50);
        String name = Values.clean(request.name(), 50);
        boolean active = request.active();

        // 비활성·환자 계정까지 포함해 중복을 막는다. 동시 요청은 uq_account_login(23505 → LOGIN_ID_TAKEN)이 최종 차단한다.
        if (db.count("SELECT count(*) FROM user_account WHERE login_id = :login", Map.of("login", loginId)) > 0) {
            throw ApiException.conflict("LOGIN_ID_TAKEN", "이미 사용 중인 로그인 아이디입니다.");
        }
        long accountId = db.insert("""
                INSERT INTO user_account(login_id, password_hash, role, account_status)
                VALUES (:login, :password, :role, :status) RETURNING account_id
                """, Values.map("login", loginId, "password", passwords.encode(request.initialPassword()),
                "role", request.role().name(), "status", active ? "ACTIVE" : "INACTIVE"));
        db.update("""
                INSERT INTO staff(account_id, staff_name, staff_active)
                VALUES (:account, :name, :active)
                """, Values.map("account", accountId, "name", name, "active", active));

        return Values.map("accountId", accountId, "loginId", loginId, "name", name,
                "role", request.role().name(), "active", active);
    }

    /** 직원 계정(PATIENT 제외)을 역할·활성 여부로 조회한다. accountId 오름차순, 결과가 없으면 빈 배열. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(HospitalPrincipal user, Role role, Boolean active) {
        user.require(Role.ADMIN);
        if (role == Role.PATIENT) { throw ApiException.badRequest(); }
        StringBuilder sql = new StringBuilder("""
                SELECT a.account_id, a.login_id, a.role, s.staff_name,
                       (a.account_status = 'ACTIVE' AND s.staff_active) AS active
                  FROM user_account a JOIN staff s ON s.account_id = a.account_id
                 WHERE a.role <> 'PATIENT'
                """);
        Map<String, Object> parameters = new HashMap<>();
        if (role != null) {
            sql.append(" AND a.role = :role");
            parameters.put("role", role.name());
        }
        if (active != null) {
            sql.append(active ? " AND a.account_status = 'ACTIVE' AND s.staff_active"
                              : " AND (a.account_status <> 'ACTIVE' OR NOT s.staff_active)");
        }
        sql.append(" ORDER BY a.account_id");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream()
                .map(row -> Values.map("accountId", Values.id(row, "account_id"),
                        "loginId", row.get("login_id"), "name", row.get("staff_name"),
                        "role", row.get("role"), "active", row.get("active")))
                .toList();
        return Map.of("items", items);
    }

    /** 직원 계정 한 건과 연결된 staff·doctor 식별자를 조회한다. 직원 계정이 아니거나 없으면 404. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(HospitalPrincipal user, long accountId) {
        user.require(Role.ADMIN);
        if (accountId <= 0) { throw ApiException.badRequest(); }
        Map<String, Object> row = db.one("""
                SELECT a.account_id, a.login_id, a.role, s.staff_id, s.staff_name, d.doctor_id,
                       (a.account_status = 'ACTIVE' AND s.staff_active) AS active
                  FROM user_account a
                  JOIN staff s ON s.account_id = a.account_id
                  LEFT JOIN doctor d ON d.account_id = a.account_id
                 WHERE a.account_id = :id AND a.role <> 'PATIENT'
                """, Map.of("id", accountId));
        return Values.map("accountId", Values.id(row, "account_id"), "loginId", row.get("login_id"),
                "name", row.get("staff_name"), "role", row.get("role"), "active", row.get("active"),
                "staffId", Values.id(row, "staff_id"), "doctorId", Values.nullableId(row, "doctor_id"));
    }

    /**
     * 이름·역할·활성 상태를 보낸 것만 수정한다.
     * 관리자 보호는 pg_advisory_xact_lock(8002, 1) 아래에서 검사·변경한다(관리자 역할·활성 변경 경로가 공유할 고정 잠금).
     * 인증은 요청마다 DB의 현재 역할·활성 상태를 다시 읽으므로 변경 직후부터 새 값으로 접근이 판단된다.
     */
    @Transactional
    public Map<String, Object> update(HospitalPrincipal user, long accountId, StaffAccountController.UpdateRequest request) {
        user.require(Role.ADMIN);
        if (accountId <= 0) { throw ApiException.badRequest(); }
        if (request.name() == null && request.role() == null && request.active() == null) { throw ApiException.badRequest(); }
        if (request.role() == Role.PATIENT) { throw ApiException.badRequest(); }
        String requestedName = request.name() == null ? null : Values.clean(request.name(), 50);

        db.rows("SELECT pg_advisory_xact_lock(8002, 1)", Map.of());
        Map<String, Object> current = db.one("""
                SELECT a.role, a.account_status, a.login_id, s.staff_name, s.staff_active
                  FROM user_account a JOIN staff s ON s.account_id = a.account_id
                 WHERE a.account_id = :id AND a.role <> 'PATIENT' FOR UPDATE OF a, s
                """, Map.of("id", accountId));

        Role currentRole = Role.valueOf(Values.text(current, "role"));
        boolean currentActive = "ACTIVE".equals(current.get("account_status")) && Boolean.TRUE.equals(current.get("staff_active"));
        Role newRole = request.role() == null ? currentRole : request.role();
        boolean newActive = request.active() == null ? currentActive : request.active();
        String newName = requestedName == null ? Values.text(current, "staff_name") : requestedName;

        if (newRole != currentRole && newRole != Role.DOCTOR
                && db.count("SELECT count(*) FROM doctor WHERE account_id = :id", Map.of("id", accountId)) > 0) {
            throw ApiException.conflict("ACCOUNT_ROLE_CONFLICT", "연결된 의사 프로필 때문에 역할을 변경할 수 없습니다.");
        }
        if (currentRole == Role.ADMIN && currentActive && !(newRole == Role.ADMIN && newActive)
                && db.count("""
                        SELECT count(*) FROM user_account a JOIN staff s ON s.account_id = a.account_id
                         WHERE a.role = 'ADMIN' AND a.account_status = 'ACTIVE' AND s.staff_active
                           AND a.account_id <> :id
                        """, Map.of("id", accountId)) == 0) {
            throw ApiException.conflict("LAST_ACTIVE_ADMIN", "마지막 활성 관리자 계정을 비활성화하거나 역할을 바꿀 수 없습니다.");
        }

        if (request.role() != null) {
            db.update("UPDATE user_account SET role = :role WHERE account_id = :id",
                    Values.map("role", newRole.name(), "id", accountId));
        }
        if (request.active() != null) {
            db.update("UPDATE user_account SET account_status = :status WHERE account_id = :id",
                    Values.map("status", newActive ? "ACTIVE" : "INACTIVE", "id", accountId));
            db.update("UPDATE staff SET staff_active = :active WHERE account_id = :id",
                    Values.map("active", newActive, "id", accountId));
        }
        if (requestedName != null) {
            db.update("UPDATE staff SET staff_name = :name WHERE account_id = :id", Values.map("name", newName, "id", accountId));
            db.update("UPDATE doctor SET doctor_name = :name WHERE account_id = :id", Values.map("name", newName, "id", accountId));
        }
        return Values.map("accountId", accountId, "loginId", Values.text(current, "login_id"), "name", newName,
                "role", newRole.name(), "active", newActive);
    }
}
