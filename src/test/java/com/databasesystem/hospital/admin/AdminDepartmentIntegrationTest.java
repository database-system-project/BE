package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDepartmentIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/departments";

    private ResultActions create(Actor actor, String name, Object active) throws Exception {
        return postAs(PATH, actor, Values.map("name", name, "active", active));
    }

    private ResultActions patchAs(Actor actor, Object id, String json) throws Exception {
        var request = patch(PATH + "/" + id).contentType("application/json").content(json);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private long createdId(Actor admin, String name, boolean active) throws Exception {
        String json = create(admin, name, active).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("departmentId").asLong();
    }

    private List<String> names(String query, Actor admin) throws Exception {
        String json = getAs(PATH + query, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("items").findValuesAsText("name");
    }

    @Test
    void createsADepartmentAndTrimsTheName() throws Exception {
        Actor admin = staff(Role.ADMIN);
        create(admin, "  내과  ", true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentId").isNumber())
                .andExpect(jsonPath("$.name").value("내과")).andExpect(jsonPath("$.active").value(true));
        create(admin, "외과", false).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.queryForMap("SELECT department_name, department_active FROM department WHERE department_name = '내과'"))
                .containsEntry("department_active", true);
    }

    @Test
    void duplicateNamesAreRejectedIgnoringCaseSpacesAndActiveState() throws Exception {
        Actor admin = staff(Role.ADMIN);
        createdId(admin, "Internal", false);
        for (String name : List.of("Internal", "internal", "  INTERNAL ")) {
            create(admin, name, true).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_DEPARTMENT_NAME"))
                    .andExpect(jsonPath("$.message").value("같은 이름의 진료과가 이미 있습니다."));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM department", Long.class)).isEqualTo(1L);
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        List<Map<String, Object>> bodies = List.of(
                Values.map("name", "   ", "active", true), Values.map("name", "가".repeat(51), "active", true),
                Values.map("active", true), Values.map("name", "내과"), Values.map("name", "내과", "active", "true"),
                Values.map("name", "내과", "active", true, "extra", 1));
        for (Map<String, Object> body : bodies) {
            postAs(PATH, admin, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM department", Long.class)).isZero();
    }

    @Test
    void listFiltersSortsAndReturnsEmptyArray() throws Exception {
        Actor admin = staff(Role.ADMIN);
        createdId(admin, "정형외과", true);
        createdId(admin, "내과", true);
        createdId(admin, "Pediatrics", false);
        createdId(admin, "외과", false);

        assertThat(names("", admin)).hasSize(4).containsExactlyInAnyOrder("정형외과", "내과", "Pediatrics", "외과");
        assertThat(names("?active=true", admin)).containsExactlyInAnyOrder("정형외과", "내과");
        assertThat(names("?active=false", admin)).containsExactlyInAnyOrder("Pediatrics", "외과");
        assertThat(names("?keyword=외과", admin)).containsExactlyInAnyOrder("정형외과", "외과");
        assertThat(names("?keyword=pedia", admin)).containsExactly("Pediatrics");
        assertThat(names("?keyword=외과&active=false", admin)).containsExactly("외과");
        assertThat(names("?keyword=%25", admin)).isEmpty();
        getAs(PATH + "?keyword=없는과", admin).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        getAs(PATH + "?active=maybe", admin).andExpect(status().isBadRequest());
    }

    @Test
    void detailShowsTheDepartmentOrNotFound() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long id = createdId(admin, "내과", false);
        getAs(PATH + "/" + id, admin).andExpect(status().isOk()).andExpect(jsonPath("$.departmentId").value(id))
                .andExpect(jsonPath("$.name").value("내과")).andExpect(jsonPath("$.active").value(false));
        getAs(PATH + "/999999999", admin).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        for (String bad : List.of("0", "-1", "abc")) {
            getAs(PATH + "/" + bad, admin).andExpect(status().isBadRequest());
        }
    }

    @Test
    void updatesOnlyTheSentFieldsAndKeepsDoctorsAndReservations() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long id = doctor.departmentId();
        long reservation = reservation(patient(), doctor, NOW.plusSeconds(3600));

        patchAs(admin, id, "{\"active\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.departmentId").value(id));
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM reservation WHERE reservation_id = ?", String.class, reservation))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT department_id FROM doctor WHERE doctor_id = ?", Long.class, doctor.doctorId()))
                .isEqualTo(id);

        patchAs(admin, id, "{\"name\":\"  새이름  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새이름")).andExpect(jsonPath("$.active").value(false));
        patchAs(admin, id, "{\"name\":\"새이름\",\"active\":true}").andExpect(status().isOk());
    }

    @Test
    void renameToAnExistingNameConflictsButKeepingOwnNameIsFine() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = createdId(admin, "내과", true);
        createdId(admin, "외과", false);
        patchAs(admin, a, "{\"name\":\" 외과 \"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEPARTMENT_NAME"));
        patchAs(admin, a, "{\"name\":\"내과\"}").andExpect(status().isOk());
        patchAs(admin, a, "{\"name\":\"  내과  \"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT department_name FROM department WHERE department_id = ?", String.class, a))
                .isEqualTo("내과");
    }

    @Test
    void invalidUpdatesChangeNothing() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long id = createdId(admin, "내과", true);
        for (String json : List.of("{}", "{\"name\":null}", "{\"active\":null}", "{\"name\":\"  \"}",
                "{\"name\":\"" + "가".repeat(51) + "\"}", "{\"name\":1}", "{\"active\":\"false\"}", "{\"active\":0}",
                "{\"departmentId\":5}", "{\"name\":\"외과\",\"extra\":true}", "[]", "not-json")) {
            patchAs(admin, id, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (String bad : List.of("0", "-1", "abc")) {
            patchAs(admin, bad, "{\"active\":false}").andExpect(status().isBadRequest());
        }
        patchAs(admin, 999_999_999L, "{\"active\":false}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForMap("SELECT department_name, department_active FROM department WHERE department_id = ?", id))
                .containsEntry("department_name", "내과").containsEntry("department_active", true);
    }

    @Test
    void onlyAdminsCanManageDepartments() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long id = doctor.departmentId();
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), doctor.actor(), patient())) {
            create(other, "외과", true).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            getAs(PATH, other).andExpect(status().isForbidden());
            getAs(PATH + "/" + id, other).andExpect(status().isForbidden());
            patchAs(other, id, "{\"active\":false}").andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        postAs(PATH, admin, null).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM department", Long.class)).isEqualTo(1L);
    }
}
