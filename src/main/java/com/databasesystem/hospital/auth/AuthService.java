package com.databasesystem.hospital.auth;

import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Values;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

@Service
public class AuthService {
    private final Database db;
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final Clock clock;

    public AuthService(Database db, AccountRepository accounts, PasswordEncoder passwords,
                       TokenService tokens, Clock clock) {
        this.db = db;
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> signup(AuthController.Signup request) {
        String name = Values.clean(request.name(), 50);
        String login = Values.clean(request.loginId(), 50);
        String phone = Values.phone(request.phone());
        if (request.birthDate().isAfter(LocalDate.now(clock.withZone(Values.SEOUL)))
                || !("M".equals(request.gender()) || "F".equals(request.gender()))) {
            throw ApiException.badRequest();
        }
        long accountId = db.insert("""
                INSERT INTO user_account(login_id, password_hash, role)
                VALUES (:login, :password, 'PATIENT') RETURNING account_id
                """, Map.of("login", login, "password", passwords.encode(request.password())));
        long patientId = db.insert("""
                INSERT INTO patient(account_id, patient_name, birth_date, gender, phone, privacy_agreed_at)
                VALUES (:account, :name, :birth, :gender, :phone, :agreed) RETURNING patient_id
                """, Values.map("account", accountId, "name", name, "birth", request.birthDate(),
                "gender", request.gender(), "phone", phone, "agreed", clock.instant()));
        return Values.map("accountId", accountId, "patientId", patientId, "role", "PATIENT");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> login(AuthController.Login request) {
        Map<String, Object> row = accounts.byLogin(request.loginId().strip()).orElseThrow(this::failed);
        if (!accounts.isActive(row) || !passwords.matches(request.password(), (String) row.get("password_hash"))) {
            throw failed();
        }
        HospitalPrincipal principal = accounts.principal(row);
        Map<String, Object> user = Values.map("accountId", principal.accountId(), "name", principal.name(),
                "role", principal.role().name());
        switch (principal.role()) {
            case PATIENT -> user.put("patientId", principal.patientId());
            case DOCTOR -> user.put("doctorId", principal.doctorId());
            default -> user.put("staffId", principal.staffId());
        }
        return Values.map("accessToken", tokens.issue(principal), "tokenType", "Bearer",
                "expiresIn", tokens.ttl(), "user", user);
    }

    private ApiException failed() {
        return new ApiException(401, "INVALID_CREDENTIALS", "아이디 또는 비밀번호를 확인하세요.");
    }
}
