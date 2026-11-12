package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffAccountIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/staff-accounts";

    private Map<String, Object> body(String login, String role, boolean active) {
        return Values.map("loginId", login, "initialPassword", "DemoPass!123", "name", "이정훈",
                "role", role, "active", active);
    }

    private long accountCount() { return jdbc.queryForObject("SELECT count(*) FROM user_account", Long.class); }

    @Test
    void adminCreatesAnAccountAndStaffProfileInOneStep() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long before = accountCount();
        postAs(PATH, admin, body("doctor_demo", "DOCTOR", true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value("doctor_demo"))
                .andExpect(jsonPath("$.name").value("이정훈"))
                .andExpect(jsonPath("$.role").value("DOCTOR"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.accountId").isNumber())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.initialPassword").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(accountCount()).isEqualTo(before + 1);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT a.role, a.account_status, a.password_hash, s.staff_name, s.staff_active
                  FROM user_account a JOIN staff s ON s.account_id = a.account_id WHERE a.login_id = 'doctor_demo'
                """);
        assertThat(row).containsEntry("role", "DOCTOR").containsEntry("account_status", "ACTIVE")
                .containsEntry("staff_name", "이정훈").containsEntry("staff_active", true);
        String hash = (String) row.get("password_hash");
        assertThat(hash).doesNotContain("DemoPass!123");
        assertThat(passwords.matches("DemoPass!123", hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM doctor", Long.class)).isZero();
    }

    @Test
    void createdStaffCanLogInAndTheInactiveOneCannot() throws Exception {
        Actor admin = staff(Role.ADMIN);
        postAs(PATH, admin, body("desk_demo", "RECEPTION", true)).andExpect(status().isCreated());
        postAs(PATH, admin, body("off_demo", "CASHIER", false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("loginId", "desk_demo", "password", "DemoPass!123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("RECEPTION"));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("loginId", "off_demo", "password", "DemoPass!123"))))
                .andExpect(status().isUnauthorized());
        Map<String, Object> off = jdbc.queryForMap("""
                SELECT a.account_status, s.staff_active FROM user_account a JOIN staff s ON s.account_id = a.account_id
                 WHERE a.login_id = 'off_demo'
                """);
        assertThat(off).containsEntry("account_status", "INACTIVE").containsEntry("staff_active", false);
    }

    @Test
    void duplicateLoginIdIsRejectedEvenForInactiveOrPatientAccounts() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor patient = patient();
        postAs(PATH, admin, body("same_login", "CASHIER", false)).andExpect(status().isCreated());
        long afterFirst = accountCount();
        for (String login : List.of("same_login", patient.loginId())) {
            postAs(PATH, admin, body(login, "DOCTOR", true))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LOGIN_ID_TAKEN"))
                    .andExpect(jsonPath("$.message").value("이미 사용 중인 로그인 아이디입니다."));
        }
        assertThat(accountCount()).isEqualTo(afterFirst);
    }

    @Test
    void onlyAdminsCanCreateStaffAccounts() throws Exception {
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), staff(Role.DOCTOR), patient())) {
            postAs(PATH, other, body("blocked_" + other.accountId(), "CASHIER", true))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(post(PATH).contentType("application/json")
                        .content(mapper.writeValueAsString(body("anonymous", "CASHIER", true))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE login_id = 'anonymous' "
                + "OR login_id LIKE 'blocked_%'", Long.class)).isZero();
    }

    @Test
    void invalidRequestsAreRejectedWithoutCreatingAnything() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long before = accountCount();
        var patientRole = body("p1", "PATIENT", true);
        var unknownRole = body("p2", "NURSE", true);
        var blankName = body("p3", "DOCTOR", true); blankName.put("name", "   ");
        var longLogin = body("x".repeat(51), "DOCTOR", true);
        var shortPassword = body("p4", "DOCTOR", true); shortPassword.put("initialPassword", "short");
        var missingActive = body("p5", "DOCTOR", true); missingActive.remove("active");
        var stringActive = body("p6", "DOCTOR", true); stringActive.put("active", "true");
        var extraField = body("p7", "DOCTOR", true); extraField.put("staffId", 1);
        for (var invalid : List.of(patientRole, unknownRole, blankName, longLogin, shortPassword,
                missingActive, stringActive, extraField)) {
            postAs(PATH, admin, invalid).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(accountCount()).isEqualTo(before);
    }

    @Test
    void surroundingSpacesAreTrimmedBeforeSavingAndChecking() throws Exception {
        Actor admin = staff(Role.ADMIN);
        var request = body("  trimmed_login  ", "CASHIER", true); request.put("name", "  김수납  ");
        postAs(PATH, admin, request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value("trimmed_login"))
                .andExpect(jsonPath("$.name").value("김수납"));
        postAs(PATH, admin, body("trimmed_login", "CASHIER", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_ID_TAKEN"));
    }
}
