package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDoctorIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/doctors";

    private long department(String name) {
        return jdbc.queryForObject("INSERT INTO department(department_name) VALUES (?) RETURNING department_id",
                Long.class, name + UUID.randomUUID().toString().substring(0, 6));
    }

    private ResultActions link(Actor admin, Object accountId, Object departmentId, String name, Object active) throws Exception {
        return postAs(PATH, admin, Values.map("accountId", accountId, "departmentId", departmentId, "name", name, "active", active));
    }

    private ResultActions patchAs(Actor actor, Object id, String json) throws Exception {
        var request = patch(PATH + "/" + id).contentType("application/json").content(json);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private long doctorCount() { return jdbc.queryForObject("SELECT count(*) FROM doctor", Long.class); }

    @Test
    void linksADoctorProfileAndSyncsTheStaffName() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor account = staff(Role.DOCTOR);
        long dept = department("내과");
        link(admin, account.accountId(), dept, "  이정훈  ", true).andExpect(status().isCreated())
                .andExpect(jsonPath("$.doctorId").isNumber())
                .andExpect(jsonPath("$.accountId").value(account.accountId()))
                .andExpect(jsonPath("$.departmentId").value(dept))
                .andExpect(jsonPath("$.departmentName").value(org.hamcrest.Matchers.startsWith("내과")))
                .andExpect(jsonPath("$.name").value("이정훈")).andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("SELECT staff_name FROM staff WHERE account_id = ?", String.class, account.accountId()))
                .isEqualTo("이정훈");
        assertThat(jdbc.queryForObject("SELECT doctor_name FROM doctor WHERE account_id = ?", String.class, account.accountId()))
                .isEqualTo("이정훈");
    }

    @Test
    void rejectsAlreadyLinkedWrongRoleAndMissingTargets() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long dept = department("내과");
        Actor account = staff(Role.DOCTOR);
        link(admin, account.accountId(), dept, "이정훈", true).andExpect(status().isCreated());
        link(admin, account.accountId(), dept, "다른이름", true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_LINKED"))
                .andExpect(jsonPath("$.message").value("이미 의사에 연결된 계정입니다."));

        for (Actor wrong : List.of(staff(Role.CASHIER), staff(Role.ADMIN), patient())) {
            link(admin, wrong.accountId(), dept, "이정훈", true).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_DOCTOR_ACCOUNT"))
                    .andExpect(jsonPath("$.message").value("DOCTOR 역할 계정만 연결할 수 있습니다."));
        }
        Actor free = staff(Role.DOCTOR);
        link(admin, 999_999_999L, dept, "이정훈", true).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        link(admin, free.accountId(), 999_999_999L, "이정훈", true).andExpect(status().isNotFound());
        assertThat(doctorCount()).isEqualTo(1L);
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor account = staff(Role.DOCTOR);
        long dept = department("내과");
        List<Map<String, Object>> bodies = List.of(
                Values.map("accountId", account.accountId(), "departmentId", dept, "name", "  ", "active", true),
                Values.map("accountId", account.accountId(), "departmentId", dept, "name", "가".repeat(51), "active", true),
                Values.map("departmentId", dept, "name", "이정훈", "active", true),
                Values.map("accountId", account.accountId(), "name", "이정훈", "active", true),
                Values.map("accountId", account.accountId(), "departmentId", dept, "active", true),
                Values.map("accountId", account.accountId(), "departmentId", dept, "name", "이정훈"),
                Values.map("accountId", "13", "departmentId", dept, "name", "이정훈", "active", true),
                Values.map("accountId", 0, "departmentId", dept, "name", "이정훈", "active", true),
                Values.map("accountId", account.accountId(), "departmentId", -1, "name", "이정훈", "active", true),
                Values.map("accountId", account.accountId(), "departmentId", dept, "name", "이정훈", "active", "true"),
                Values.map("accountId", account.accountId(), "departmentId", dept, "name", "이정훈", "active", true, "extra", 1));
        for (Map<String, Object> body : bodies) {
            postAs(PATH, admin, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(doctorCount()).isZero();
    }

    @Test
    void listFiltersSortsAndReturnsEmptyArray() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long internal = department("내과");
        long surgery = department("외과");
        Actor a = staff(Role.DOCTOR), b = staff(Role.DOCTOR), c = staff(Role.DOCTOR);
        link(admin, a.accountId(), internal, "김철수", true).andExpect(status().isCreated());
        link(admin, b.accountId(), internal, "김영희", false).andExpect(status().isCreated());
        link(admin, c.accountId(), surgery, "이정훈", true).andExpect(status().isCreated());

        assertThat(items(PATH, admin)).hasSize(3);
        assertThat(items(PATH + "?departmentId=" + internal, admin)).containsExactlyInAnyOrder("김철수", "김영희");
        assertThat(items(PATH + "?active=false", admin)).containsExactly("김영희");
        assertThat(items(PATH + "?keyword=김", admin)).containsExactlyInAnyOrder("김철수", "김영희");
        assertThat(items(PATH + "?keyword=정훈&departmentId=" + surgery + "&active=true", admin)).containsExactly("이정훈");
        assertThat(items(PATH + "?departmentId=999999999", admin)).isEmpty();
        getAs(PATH + "?keyword=없는사람", admin).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        for (String bad : List.of("?departmentId=0", "?departmentId=abc", "?active=maybe")) {
            getAs(PATH + bad, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    private List<String> items(String path, Actor admin) throws Exception {
        String json = getAs(path, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("items").findValuesAsText("name");
    }

    @Test
    void detailShowsInactiveDoctorsAndNotFound() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        jdbc.update("UPDATE doctor SET doctor_active = FALSE WHERE doctor_id = ?", doctor.doctorId());
        getAs(PATH + "/" + doctor.doctorId(), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(doctor.doctorId()))
                .andExpect(jsonPath("$.accountId").value(doctor.actor().accountId()))
                .andExpect(jsonPath("$.departmentId").value(doctor.departmentId()))
                .andExpect(jsonPath("$.name").value("테스트의사")).andExpect(jsonPath("$.active").value(false));
        getAs(PATH + "/999999999", admin).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        for (String bad : List.of("0", "-1", "abc")) {
            getAs(PATH + "/" + bad, admin).andExpect(status().isBadRequest());
        }
    }

    @Test
    void updatesOnlyTheSentFieldsAndKeepsReservations() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long reservation = reservation(patient(), doctor, NOW.plusSeconds(3600));
        long other = department("외과");

        patchAs(admin, doctor.doctorId(), "{\"active\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.name").value("테스트의사"))
                .andExpect(jsonPath("$.departmentId").value(doctor.departmentId()));
        patchAs(admin, doctor.doctorId(), "{\"name\":\"  새이름  \",\"departmentId\":" + other + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새이름")).andExpect(jsonPath("$.departmentId").value(other))
                .andExpect(jsonPath("$.departmentName").value(org.hamcrest.Matchers.startsWith("외과")))
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.accountId").value(doctor.actor().accountId()));
        assertThat(jdbc.queryForObject("SELECT staff_name FROM staff WHERE account_id = ?", String.class, doctor.actor().accountId()))
                .isEqualTo("새이름");
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM reservation WHERE reservation_id = ?", String.class, reservation))
                .isEqualTo("RESERVED");
        patchAs(admin, doctor.doctorId(), "{\"departmentId\":999999999}").andExpect(status().isNotFound());
        patchAs(admin, doctor.doctorId(), "{\"active\":true}").andExpect(status().isOk());
    }

    @Test
    void invalidUpdatesChangeNothing() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        for (String json : List.of("{}", "{\"name\":null}", "{\"active\":null}", "{\"departmentId\":null}", "{\"name\":\"  \"}",
                "{\"name\":\"" + "가".repeat(51) + "\"}", "{\"name\":1}", "{\"active\":\"false\"}", "{\"departmentId\":\"1\"}",
                "{\"departmentId\":0}", "{\"departmentId\":-3}", "{\"departmentId\":1.5}",
                "{\"accountId\":1}", "{\"name\":\"정상\",\"extra\":true}", "[]", "not-json")) {
            patchAs(admin, doctor.doctorId(), json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (String bad : List.of("0", "-1", "abc")) {
            patchAs(admin, bad, "{\"active\":false}").andExpect(status().isBadRequest());
        }
        patchAs(admin, 999_999_999L, "{\"active\":false}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForMap("SELECT doctor_name, doctor_active, department_id FROM doctor WHERE doctor_id = ?", doctor.doctorId()))
                .containsEntry("doctor_name", "테스트의사").containsEntry("doctor_active", true)
                .containsEntry("department_id", doctor.departmentId());
    }

    @Test
    void onlyAdminsCanManageDoctors() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        Actor account = staff(Role.DOCTOR);
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), staff(Role.DOCTOR), patient(), doctor.actor())) {
            link(other, account.accountId(), doctor.departmentId(), "이정훈", true)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            getAs(PATH, other).andExpect(status().isForbidden());
            getAs(PATH + "/" + doctor.doctorId(), other).andExpect(status().isForbidden());
            patchAs(other, doctor.doctorId(), "{\"active\":false}").andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        assertThat(doctorCount()).isEqualTo(1L);
        getAs(PATH, admin).andExpect(status().isOk());
    }
}
