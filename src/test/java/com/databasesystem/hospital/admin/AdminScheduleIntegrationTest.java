package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminScheduleIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/schedules";

    private ResultActions create(Actor actor, Object doctorId, Object date, Object closed) throws Exception {
        var request = post(PATH).contentType("application/json")
                .content(mapper.writeValueAsString(Values.map("doctorId", doctorId, "date", date, "closed", closed)));
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private ResultActions patchAs(Actor actor, Object id, String json) throws Exception {
        var request = patch(PATH + "/" + id).contentType("application/json").content(json);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private ResultActions deleteAs(Actor actor, Object id) throws Exception {
        var request = delete(PATH + "/" + id);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private long createdId(Actor admin, long doctorId, String date, boolean closed) throws Exception {
        String json = create(admin, doctorId, date, closed).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("scheduleId").asLong();
    }

    private List<Long> ids(String query, Actor admin) throws Exception {
        String json = getAs(PATH + query, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("items").findValues("scheduleId").stream().map(node -> node.asLong()).toList();
    }

    private long scheduleCount() { return jdbc.queryForObject("SELECT count(*) FROM doctor_schedule", Long.class); }

    private void cancelledReservation(long reservationId) {
        jdbc.update("UPDATE reservation SET reservation_status = 'CANCELLED', reservation_cancelled_at = now() WHERE reservation_id = ?",
                reservationId);
    }

    @Test
    void createsAScheduleWithTheFixedOperatingHours() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        create(admin, doctor.doctorId(), "2026-11-11", false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleId").isNumber()).andExpect(jsonPath("$.doctorId").value(doctor.doctorId()))
                .andExpect(jsonPath("$.doctorName").value("테스트의사")).andExpect(jsonPath("$.date").value("2026-11-11"))
                .andExpect(jsonPath("$.receptionStart").value("09:00")).andExpect(jsonPath("$.closingTime").value("17:00"))
                .andExpect(jsonPath("$.lunchStart").value("12:30")).andExpect(jsonPath("$.lunchEnd").value("13:30"))
                .andExpect(jsonPath("$.closed").value(false));
        create(admin, doctor.doctorId(), "2026-11-12", true).andExpect(status().isCreated()).andExpect(jsonPath("$.closed").value(true));
        assertThat(jdbc.queryForObject("SELECT is_closed FROM doctor_schedule WHERE schedule_date = DATE '2026-11-12'", Boolean.class)).isTrue();
    }

    @Test
    void duplicateDoctorAndDateIsRejectedAlsoWhenConcurrent() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        create(admin, doctor.doctorId(), "2026-11-10", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SCHEDULE"))
                .andExpect(jsonPath("$.message").value("해당 의사의 같은 날짜 일정이 이미 있습니다."));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> create(admin, doctor.doctorId(), "2026-12-01", false).andReturn().getResponse().getStatus(),
                    () -> create(admin, doctor.doctorId(), "2026-12-01", true).andReturn().getResponse().getStatus());
            for (Future<Integer> result : pool.invokeAll(calls)) { statuses.add(result.get()); }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(scheduleCount()).isEqualTo(2L);
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long id = doctor.doctorId();
        List<String> bodies = List.of("{}",
                "{\"date\":\"2026-11-11\",\"closed\":false}", "{\"doctorId\":" + id + ",\"closed\":false}",
                "{\"doctorId\":" + id + ",\"date\":\"2026-11-11\"}",
                "{\"doctorId\":null,\"date\":\"2026-11-11\",\"closed\":false}",
                "{\"doctorId\":0,\"date\":\"2026-11-11\",\"closed\":false}", "{\"doctorId\":-1,\"date\":\"2026-11-11\",\"closed\":false}",
                "{\"doctorId\":\"" + id + "\",\"date\":\"2026-11-11\",\"closed\":false}",
                "{\"doctorId\":" + id + ",\"date\":\"2026/11/11\",\"closed\":false}",
                "{\"doctorId\":" + id + ",\"date\":\"2026-13-01\",\"closed\":false}",
                "{\"doctorId\":" + id + ",\"date\":\"20261111\",\"closed\":false}",
                "{\"doctorId\":" + id + ",\"date\":20261111,\"closed\":false}",
                "{\"doctorId\":" + id + ",\"date\":\"2026-11-11\",\"closed\":\"false\"}",
                "{\"doctorId\":" + id + ",\"date\":\"2026-11-11\",\"closed\":false,\"receptionStart\":\"10:00\"}",
                "{\"doctorId\":" + id + ",\"date\":\"2026-11-11\",\"closed\":false,\"lunchStart\":\"12:00\"}",
                "{\"doctorId\":" + id + ",\"date\":\"2026-11-11\",\"closed\":false,\"closingTime\":\"18:00\"}",
                "[]", "not-json");
        for (String json : bodies) {
            mvc.perform(post(PATH)
                            .header("Authorization", "Bearer " + admin.token()).contentType("application/json").content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        create(admin, 999_999_999L, "2026-11-11", false).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(scheduleCount()).isEqualTo(1L);
    }

    @Test
    void listDefaultsToTodayAndFiltersByDoctorAndInclusiveRange() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor first = doctor();
        Doctor second = doctor();
        long today1 = first.scheduleId();
        long today2 = second.scheduleId();
        long d11 = createdId(admin, first.doctorId(), "2026-11-11", false);
        long d12 = createdId(admin, first.doctorId(), "2026-11-12", true);
        long d12b = createdId(admin, second.doctorId(), "2026-11-12", false);

        assertThat(ids("", admin)).containsExactly(today1, today2);
        assertThat(ids("?doctorId=" + first.doctorId(), admin)).containsExactly(today1);
        assertThat(ids("?from=2026-11-11&to=2026-11-12", admin)).containsExactly(d11, d12, d12b);
        assertThat(ids("?from=2026-11-12&to=2026-11-12", admin)).containsExactly(d12, d12b);
        assertThat(ids("?from=2026-11-11", admin)).containsExactly(d11, d12, d12b);
        assertThat(ids("?to=2026-11-10", admin)).containsExactly(today1, today2);
        assertThat(ids("?doctorId=" + first.doctorId() + "&from=2026-11-10&to=2026-11-30", admin)).containsExactly(today1, d11, d12);
        getAs(PATH + "?from=2030-01-01&to=2030-01-31", admin).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        getAs(PATH + "?doctorId=999999999", admin).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        getAs(PATH + "?from=2026-11-10", admin).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].closed").value(false))
                .andExpect(jsonPath("$.items[0].doctorName").value("테스트의사"));
    }

    @Test
    void invalidListQueriesAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        for (String query : List.of("?doctorId=0", "?doctorId=abc", "?doctorId=", "?from=2026/11/10", "?from=", "?to=bad",
                "?from=2026-11-12&to=2026-11-11", "?closed=true", "?from=2026-11-10&from=2026-11-11")) {
            getAs(PATH + query, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void detailShowsClosedSchedulesAndNotFound() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        jdbc.update("UPDATE doctor SET doctor_active = FALSE WHERE doctor_id = ?", doctor.doctorId());
        long closed = createdId(admin, doctor.doctorId(), "2026-11-20", true);
        getAs(PATH + "/" + closed, admin).andExpect(status().isOk()).andExpect(jsonPath("$.scheduleId").value(closed))
                .andExpect(jsonPath("$.closed").value(true)).andExpect(jsonPath("$.date").value("2026-11-20"))
                .andExpect(jsonPath("$.receptionStart").value("09:00"));
        getAs(PATH + "/999999999", admin).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        for (String bad : List.of("0", "-1", "abc")) { getAs(PATH + "/" + bad, admin).andExpect(status().isBadRequest()); }
    }

    @Test
    void updatesOnlyTheSentFieldsWhenNoReservationExists() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor first = doctor();
        Doctor second = doctor();
        long id = createdId(admin, first.doctorId(), "2026-11-15", false);

        patchAs(admin, id, "{\"closed\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.date").value("2026-11-15")).andExpect(jsonPath("$.doctorId").value(first.doctorId()));
        patchAs(admin, id, "{\"date\":\"2026-11-16\"}").andExpect(status().isOk()).andExpect(jsonPath("$.date").value("2026-11-16"))
                .andExpect(jsonPath("$.closed").value(true));
        patchAs(admin, id, "{\"doctorId\":" + second.doctorId() + ",\"date\":\"2026-11-17\",\"closed\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(second.doctorId())).andExpect(jsonPath("$.doctorName").value("테스트의사"))
                .andExpect(jsonPath("$.date").value("2026-11-17")).andExpect(jsonPath("$.closed").value(false));
        patchAs(admin, id, "{\"closed\":false,\"date\":\"2026-11-17\"}").andExpect(status().isOk());
        assertThat(scheduleCount()).isEqualTo(3L);
    }

    @Test
    void updateRejectsDuplicateDoctorAndDate() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long other = createdId(admin, doctor.doctorId(), "2026-11-15", false);
        patchAs(admin, other, "{\"date\":\"2026-11-10\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SCHEDULE"))
                .andExpect(jsonPath("$.message").value("변경한 의사·날짜의 일정이 이미 있습니다."));
        assertThat(jdbc.queryForObject("SELECT schedule_date::text FROM doctor_schedule WHERE schedule_id = ?", String.class, other))
                .isEqualTo("2026-11-15");
    }

    @Test
    void reservationsProtectTheScheduleEvenWhenCancelled() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        Doctor target = doctor();
        long reservation = reservation(patient(), doctor, NOW.plusSeconds(3600));
        long id = doctor.scheduleId();

        for (String json : List.of("{\"closed\":true}", "{\"date\":\"2026-11-30\"}",
                "{\"doctorId\":" + target.doctorId() + "}")) {
            patchAs(admin, id, json).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SCHEDULE_HAS_APPOINTMENTS"))
                    .andExpect(jsonPath("$.message").value("예약이 연결된 일정은 해당 변경을 할 수 없습니다."));
        }
        cancelledReservation(reservation);
        patchAs(admin, id, "{\"closed\":true}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCHEDULE_HAS_APPOINTMENTS"));
        // 값이 그대로이거나 휴진을 푸는 것은 변경이 아니거나 허용된다.
        patchAs(admin, id, "{\"closed\":false,\"date\":\"2026-11-10\",\"doctorId\":" + doctor.doctorId() + "}").andExpect(status().isOk());
        jdbc.update("UPDATE doctor_schedule SET is_closed = TRUE WHERE schedule_id = ?", id);
        patchAs(admin, id, "{\"closed\":true}").andExpect(status().isOk());
        patchAs(admin, id, "{\"closed\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(false));
        assertThat(jdbc.queryForMap("SELECT doctor_id, schedule_date::text AS d FROM doctor_schedule WHERE schedule_id = ?", id))
                .containsEntry("doctor_id", doctor.doctorId()).containsEntry("d", "2026-11-10");
    }

    @Test
    void invalidUpdatesChangeNothing() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long id = doctor.scheduleId();
        for (String json : List.of("{}", "{\"closed\":null}", "{\"date\":null}", "{\"doctorId\":null}", "{\"doctorId\":0}",
                "{\"doctorId\":-2}", "{\"doctorId\":\"1\"}", "{\"doctorId\":1.5}", "{\"date\":\"2026/11/10\"}", "{\"date\":\"2026-02-30\"}",
                "{\"date\":20261110}", "{\"closed\":\"true\"}", "{\"closed\":1}",
                "{\"receptionStart\":\"10:00\"}", "{\"lunchEnd\":\"14:00\"}", "{\"closed\":true,\"closingTime\":\"18:00\"}",
                "{\"scheduleId\":5}", "[]", "not-json")) {
            patchAs(admin, id, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (String bad : List.of("0", "-1", "abc")) { patchAs(admin, bad, "{\"closed\":true}").andExpect(status().isBadRequest()); }
        patchAs(admin, 999_999_999L, "{\"closed\":true}").andExpect(status().isNotFound());
        patchAs(admin, id, "{\"doctorId\":999999999}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForMap("SELECT is_closed, schedule_date::text AS d FROM doctor_schedule WHERE schedule_id = ?", id))
                .containsEntry("is_closed", false).containsEntry("d", "2026-11-10");
    }

    @Test
    void deletesOnlySchedulesWithoutAnyReservation() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long free = createdId(admin, doctor.doctorId(), "2026-11-25", false);
        deleteAs(admin, free).andExpect(status().isNoContent()).andExpect(content().string(""));
        deleteAs(admin, free).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        long reservation = reservation(patient(), doctor, NOW.plusSeconds(3600));
        for (int round = 0; round < 2; round++) {
            deleteAs(admin, doctor.scheduleId()).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SCHEDULE_HAS_APPOINTMENTS"))
                    .andExpect(jsonPath("$.message").value("예약이 연결된 일정은 삭제할 수 없습니다."));
            cancelledReservation(reservation); // 취소 이력도 연결 예약으로 계산한다.
        }
        assertThat(scheduleCount()).isEqualTo(1L);
        for (String bad : List.of("0", "-1", "abc")) { deleteAs(admin, bad).andExpect(status().isBadRequest()); }
    }

    @Test
    void onlyAdminsCanManageSchedules() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Doctor doctor = doctor();
        long id = doctor.scheduleId();
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), staff(Role.DOCTOR), patient(), doctor.actor())) {
            create(other, doctor.doctorId(), "2026-11-11", false).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            getAs(PATH, other).andExpect(status().isForbidden());
            getAs(PATH + "/" + id, other).andExpect(status().isForbidden());
            patchAs(other, id, "{\"closed\":true}").andExpect(status().isForbidden());
            deleteAs(other, id).andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        create(null, doctor.doctorId(), "2026-11-11", false).andExpect(status().isUnauthorized());
        patchAs(null, id, "{\"closed\":true}").andExpect(status().isUnauthorized());
        deleteAs(null, id).andExpect(status().isUnauthorized());
        assertThat(scheduleCount()).isEqualTo(1L);
        getAs(PATH, admin).andExpect(status().isOk());
    }
}
