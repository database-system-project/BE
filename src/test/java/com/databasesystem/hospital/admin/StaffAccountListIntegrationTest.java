package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffAccountListIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/staff-accounts";

    private long created(Actor admin, String login, String name, String role, boolean active) throws Exception {
        String json = postAs(PATH, admin, Values.map("loginId", login, "initialPassword", "DemoPass!123",
                "name", name, "role", role, "active", active))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("accountId").asLong();
    }

    private List<Map<String, Object>> items(String path, Actor admin) throws Exception {
        String json = getAs(path, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.convertValue(mapper.readTree(json).get("items"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
    }

    @Test
    void listsStaffOnlyInAccountIdOrderWithoutSecrets() throws Exception {
        Actor admin = staff(Role.ADMIN);
        patient();
        long doctor = created(admin, "list_doctor", "이정훈", "DOCTOR", true);
        long desk = created(admin, "list_desk", "김접수", "RECEPTION", false);

        List<Map<String, Object>> all = items(PATH, admin);
        assertThat(all).extracting(item -> ((Number) item.get("accountId")).longValue()).isSorted();
        assertThat(all).extracting(item -> item.get("role")).doesNotContain("PATIENT");
        assertThat(all).extracting(item -> ((Number) item.get("accountId")).longValue())
                .contains(admin.accountId(), doctor, desk);
        Map<String, Object> first = all.stream()
                .filter(item -> ((Number) item.get("accountId")).longValue() == doctor).findFirst().orElseThrow();
        assertThat(first).containsOnlyKeys("accountId", "loginId", "name", "role", "active")
                .containsEntry("loginId", "list_doctor").containsEntry("name", "이정훈")
                .containsEntry("role", "DOCTOR").containsEntry("active", true);
    }

    @Test
    void filtersByRoleAndActive() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long activeDoctor = created(admin, "f_doctor_on", "의사A", "DOCTOR", true);
        long inactiveDoctor = created(admin, "f_doctor_off", "의사B", "DOCTOR", false);
        long cashier = created(admin, "f_cashier", "수납C", "CASHIER", true);

        List<Map<String, Object>> doctors = items(PATH + "?role=DOCTOR", admin);
        assertThat(doctors).extracting(item -> item.get("role")).containsOnly("DOCTOR");
        assertThat(doctors).extracting(item -> ((Number) item.get("accountId")).longValue())
                .contains(activeDoctor, inactiveDoctor).doesNotContain(cashier);

        List<Map<String, Object>> inactive = items(PATH + "?active=false", admin);
        assertThat(inactive).extracting(item -> item.get("active")).containsOnly(false);
        assertThat(inactive).extracting(item -> ((Number) item.get("accountId")).longValue())
                .contains(inactiveDoctor).doesNotContain(activeDoctor, cashier);

        List<Map<String, Object>> activeDoctors = items(PATH + "?role=DOCTOR&active=true", admin);
        assertThat(activeDoctors).extracting(item -> ((Number) item.get("accountId")).longValue())
                .contains(activeDoctor).doesNotContain(inactiveDoctor, cashier);
    }

    @Test
    void emptyResultIsAnEmptyArray() throws Exception {
        Actor admin = staff(Role.ADMIN);
        getAs(PATH + "?role=CASHIER&active=false", admin)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void invalidFiltersAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        for (String query : List.of("?role=PATIENT", "?role=NURSE", "?role=doctor", "?active=maybe")) {
            getAs(PATH + query, admin).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void onlyAdminsCanList() throws Exception {
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), staff(Role.DOCTOR), patient())) {
            getAs(PATH, other).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
