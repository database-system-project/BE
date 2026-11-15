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
        long doctor = doctor().actor().accountId();
        long desk = created(admin, "list_desk", "김접수", "RECEPTION", false);

        List<Map<String, Object>> all = items(PATH, admin);
        assertThat(all).extracting(item -> ((Number) item.get("accountId")).longValue()).isSorted();
        assertThat(all).extracting(item -> item.get("role")).doesNotContain("PATIENT", "DOCTOR");
        assertThat(all).extracting(item -> ((Number) item.get("accountId")).longValue())
                .containsExactly(admin.accountId(), desk).doesNotContain(doctor);
        Map<String, Object> first = all.stream()
                .filter(item -> ((Number) item.get("accountId")).longValue() == desk).findFirst().orElseThrow();
        assertThat(first).containsOnlyKeys("accountId", "loginId", "name", "role", "active")
                .containsEntry("loginId", "list_desk").containsEntry("name", "김접수")
                .containsEntry("role", "RECEPTION").containsEntry("active", false);
    }

    @Test
    void filtersByRoleAndActive() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long activeCashier = created(admin, "f_cashier_on", "수납A", "CASHIER", true);
        long inactiveCashier = created(admin, "f_cashier_off", "수납B", "CASHIER", false);
        long receptionist = created(admin, "f_desk", "접수C", "RECEPTION", true);

        List<Map<String, Object>> cashiers = items(PATH + "?role=CASHIER", admin);
        assertThat(cashiers).extracting(item -> item.get("role")).containsOnly("CASHIER");
        assertThat(cashiers).extracting(item -> ((Number) item.get("accountId")).longValue())
                .contains(activeCashier, inactiveCashier).doesNotContain(receptionist);

        List<Map<String, Object>> inactive = items(PATH + "?active=false", admin);
        assertThat(inactive).extracting(item -> item.get("active")).containsOnly(false);
        assertThat(inactive).extracting(item -> ((Number) item.get("accountId")).longValue())
                .contains(inactiveCashier).doesNotContain(activeCashier, receptionist);

        List<Map<String, Object>> activeCashiers = items(PATH + "?role=CASHIER&active=true", admin);
        assertThat(activeCashiers).extracting(item -> ((Number) item.get("accountId")).longValue())
                .contains(activeCashier).doesNotContain(inactiveCashier, receptionist);
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
        for (String query : List.of("?role=PATIENT", "?role=DOCTOR", "?role=NURSE", "?role=doctor", "?active=maybe")) {
            getAs(PATH + query, admin).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void onlyAdminsCanList() throws Exception {
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), doctor().actor(), patient())) {
            getAs(PATH, other).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
