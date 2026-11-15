package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffAccountUpdateIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/staff-accounts/";
    private static final String LIST = "/api/v1/admin/staff-accounts";

    private org.springframework.test.web.servlet.ResultActions patchAs(Actor actor, Object id, String json) throws Exception {
        var request = patch(PATH + id).contentType("application/json").content(json);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private Map<String, Object> row(long accountId) {
        return jdbc.queryForMap("""
                SELECT a.role, a.account_status, a.token_version, s.staff_name, s.staff_active
                  FROM user_account a JOIN staff s ON s.account_id = a.account_id WHERE a.account_id = ?
                """, accountId);
    }

    @Test
    void changesOnlyTheSentFieldsAndReturnsTheNewState() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor target = staff(Role.RECEPTION);
        patchAs(admin, target.accountId(), "{\"name\":\"  박접수  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(target.accountId()))
                .andExpect(jsonPath("$.loginId").value(target.loginId()))
                .andExpect(jsonPath("$.name").value("박접수"))
                .andExpect(jsonPath("$.role").value("RECEPTION"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());
        assertThat(row(target.accountId())).containsEntry("staff_name", "박접수")
                .containsEntry("role", "RECEPTION").containsEntry("account_status", "ACTIVE");

        patchAs(admin, target.accountId(), "{\"role\":\"CASHIER\",\"active\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CASHIER"))
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.name").value("박접수"));
        assertThat(row(target.accountId())).containsEntry("role", "CASHIER")
                .containsEntry("account_status", "INACTIVE").containsEntry("staff_active", false)
                .containsEntry("staff_name", "박접수");
    }

    @Test
    void deactivationAndRoleChangeApplyToTheVeryNextRequest() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor target = staff(Role.RECEPTION);
        getAs(LIST, target).andExpect(status().isForbidden());

        patchAs(admin, target.accountId(), "{\"role\":\"ADMIN\"}").andExpect(status().isOk());
        getAs(LIST, target).andExpect(status().isOk());

        patchAs(admin, target.accountId(), "{\"active\":false}").andExpect(status().isOk());
        getAs(LIST, target).andExpect(status().isUnauthorized());

        patchAs(admin, target.accountId(), "{\"active\":true,\"role\":\"CASHIER\"}").andExpect(status().isOk());
        getAs(LIST, target).andExpect(status().isForbidden());
    }

    @Test
    void staffEndpointRejectsDoctorUpdatesAndRoleConversions() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        Actor target = staff(Role.CASHIER);

        patchAs(admin, doctor.actor().accountId(), "{\"name\":\"새이름\"}").andExpect(status().isNotFound());
        patchAs(admin, target.accountId(), "{\"role\":\"DOCTOR\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(row(target.accountId())).containsEntry("role", "CASHIER");
    }

    @Test
    void lastActiveAdminCannotBeDeactivatedOrDemoted() throws Exception {
        Actor onlyAdmin = staff(Role.ADMIN);
        for (String json : List.of("{\"active\":false}", "{\"role\":\"CASHIER\"}")) {
            patchAs(onlyAdmin, onlyAdmin.accountId(), json)
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"))
                    .andExpect(jsonPath("$.message").value("마지막 활성 관리자 계정을 비활성화하거나 역할을 바꿀 수 없습니다."));
        }
        assertThat(row(onlyAdmin.accountId())).containsEntry("role", "ADMIN").containsEntry("account_status", "ACTIVE");

        Actor inactiveAdmin = staff(Role.ADMIN);
        jdbc.update("UPDATE user_account SET account_status = 'INACTIVE' WHERE account_id = ?", inactiveAdmin.accountId());
        patchAs(onlyAdmin, onlyAdmin.accountId(), "{\"active\":false}").andExpect(status().isConflict());

        Actor secondAdmin = staff(Role.ADMIN);
        patchAs(onlyAdmin, onlyAdmin.accountId(), "{\"name\":\"이름만변경\"}").andExpect(status().isOk());
        patchAs(onlyAdmin, secondAdmin.accountId(), "{\"role\":\"CASHIER\"}").andExpect(status().isOk());
        patchAs(onlyAdmin, secondAdmin.accountId(), "{\"role\":\"ADMIN\"}").andExpect(status().isOk());
        patchAs(secondAdmin, onlyAdmin.accountId(), "{\"active\":false}").andExpect(status().isOk());
        patchAs(secondAdmin, secondAdmin.accountId(), "{\"active\":false}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"));
    }

    @Test
    void twoAdminsCannotRemoveEachOtherAtTheSameTime() throws Exception {
        Actor first = staff(Role.ADMIN);
        Actor second = staff(Role.ADMIN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            calls.add(() -> patchAs(first, second.accountId(), "{\"active\":false}").andReturn().getResponse().getStatus());
            calls.add(() -> patchAs(second, first.accountId(), "{\"active\":false}").andReturn().getResponse().getStatus());
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : pool.invokeAll(calls)) { statuses.add(result.get()); }
            assertThat(statuses).contains(200).doesNotContain(500);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM user_account a JOIN staff s ON s.account_id = a.account_id
                 WHERE a.role = 'ADMIN' AND a.account_status = 'ACTIVE' AND s.staff_active
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void invalidRequestsChangeNothing() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor target = staff(Role.CASHIER);
        List<String> bodies = List.of("{}", "{\"name\":\"   \"}", "{\"name\":\"" + "가".repeat(51) + "\"}",
                "{\"role\":\"PATIENT\"}", "{\"role\":\"NURSE\"}", "{\"role\":\"cashier\"}",
                "{\"active\":\"true\"}", "{\"active\":1}", "{\"name\":123}",
                "{\"loginId\":\"hacked\"}", "{\"password\":\"NewPass123!\"}", "{\"initialPassword\":\"NewPass123!\"}",
                "{\"name\":\"정상\",\"unknown\":true}", "[]", "not-json");
        for (String json : bodies) {
            patchAs(admin, target.accountId(), json).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (Object id : List.of("0", "-1", "abc", "1.5")) {
            patchAs(admin, id, "{\"active\":false}").andExpect(status().isBadRequest());
        }
        mvc.perform(patch(PATH + target.accountId()).header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isBadRequest());
        assertThat(row(target.accountId())).containsEntry("staff_name", "테스트직원")
                .containsEntry("role", "CASHIER").containsEntry("account_status", "ACTIVE");
        assertThat(jdbc.queryForObject("SELECT login_id FROM user_account WHERE account_id = ?", String.class,
                target.accountId())).isEqualTo(target.loginId());
    }

    @Test
    void patientAndMissingAccountsAreNotFound() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor patient = patient();
        for (long id : List.of(patient.accountId(), 999_999_999L)) {
            patchAs(admin, id, "{\"active\":false}").andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject("SELECT account_status FROM user_account WHERE account_id = ?", String.class,
                patient.accountId())).isEqualTo("ACTIVE");
    }

    @Test
    void onlyAdminsCanUpdate() throws Exception {
        Actor target = staff(Role.CASHIER);
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), doctor().actor(), patient())) {
            patchAs(other, target.accountId(), "{\"active\":false}")
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        patchAs(null, target.accountId(), "{\"active\":false}").andExpect(status().isUnauthorized());
        assertThat(row(target.accountId())).containsEntry("account_status", "ACTIVE");
    }
}
