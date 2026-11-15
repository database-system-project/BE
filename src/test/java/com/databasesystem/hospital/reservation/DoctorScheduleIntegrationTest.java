package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 현재 시각은 2026-11-10 09:00(한국 시간)으로 고정되어 있다. */
class DoctorScheduleIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/doctor/schedules";

    private ResultActions call(Actor actor, String query) throws Exception {
        var request = get(PATH + query);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private long schedule(long doctor, String date, boolean closed) {
        return jdbc.queryForObject("INSERT INTO doctor_schedule(doctor_id, schedule_date, is_closed) VALUES (?, ?::date, ?) RETURNING schedule_id",
                Long.class, doctor, date, closed);
    }

    private List<Long> ids(Actor actor, String query) throws Exception {
        String json = call(actor, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> result = new ArrayList<>();
        mapper.readTree(json).get("items").forEach(node -> result.add(node.get("scheduleId").asLong()));
        return result;
    }

    @Test
    void defaultsToTodayAndReturnsTheFixedOperatingHours() throws Exception {
        Doctor doctor = doctor();
        schedule(doctor.doctorId(), "2026-11-11", false);
        call(doctor.actor(), "").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].scheduleId").value(doctor.scheduleId()))
                .andExpect(jsonPath("$.items[0].doctorId").value(doctor.doctorId()))
                .andExpect(jsonPath("$.items[0].doctorName").value("테스트의사"))
                .andExpect(jsonPath("$.items[0].date").value("2026-11-10"))
                .andExpect(jsonPath("$.items[0].receptionStart").value("09:00"))
                .andExpect(jsonPath("$.items[0].closingTime").value("17:00"))
                .andExpect(jsonPath("$.items[0].lunchStart").value("12:30"))
                .andExpect(jsonPath("$.items[0].lunchEnd").value("13:30"))
                .andExpect(jsonPath("$.items[0].closed").value(false));
    }

    @Test
    void returnsOnlyTheLoggedInDoctorsSchedulesIncludingClosedDays() throws Exception {
        Doctor mine = doctor();
        Doctor other = doctor();
        long closed = schedule(mine.doctorId(), "2026-11-12", true);
        long later = schedule(mine.doctorId(), "2026-11-11", false);
        schedule(other.doctorId(), "2026-11-11", false);
        assertThat(ids(mine.actor(), "?from=2026-11-01&to=2026-11-30")).containsExactly(mine.scheduleId(), later, closed);
        assertThat(ids(other.actor(), "?from=2026-11-01&to=2026-11-30")).hasSize(2).doesNotContain(mine.scheduleId(), later, closed);
        call(mine.actor(), "?from=2026-11-12&to=2026-11-12").andExpect(jsonPath("$.items[0].closed").value(true));
    }

    @Test
    void filtersByInclusiveRangeAndHandlesPartialRanges() throws Exception {
        Doctor doctor = doctor();
        long d9 = schedule(doctor.doctorId(), "2026-11-09", false);
        long d10 = doctor.scheduleId();
        long d11 = schedule(doctor.doctorId(), "2026-11-11", false);
        long d12 = schedule(doctor.doctorId(), "2026-11-12", false);
        assertThat(ids(doctor.actor(), "?from=2026-11-10&to=2026-11-11")).containsExactly(d10, d11);
        assertThat(ids(doctor.actor(), "?from=2026-11-11")).containsExactly(d11, d12);
        assertThat(ids(doctor.actor(), "?to=2026-11-10")).containsExactly(d9, d10);
        assertThat(ids(doctor.actor(), "?from=2026-11-11&to=2026-11-11")).containsExactly(d11);
        assertThat(ids(doctor.actor(), "?from=2027-01-01&to=2027-01-31")).isEmpty();
        call(doctor.actor(), "?from=2027-01-01&to=2027-01-31").andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void emptyTodayReturnsAnEmptyList() throws Exception {
        Doctor doctor = doctor();
        jdbc.update("DELETE FROM doctor_schedule WHERE schedule_id = ?", doctor.scheduleId());
        assertThat(ids(doctor.actor(), "")).isEmpty();
    }

    @Test
    void rejectsInvalidQueries() throws Exception {
        Doctor doctor = doctor();
        for (String query : List.of("?from=2026-11-12&to=2026-11-10", "?from=2026/11/10", "?to=20261110", "?from=2026-13-01",
                "?from=", "?doctorId=" + doctor.doctorId(), "?extra=1", "?from=2026-11-10&from=2026-11-11")) {
            call(doctor.actor(), query).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void onlyDoctorsWithAValidTokenCanReadTheirSchedules() throws Exception {
        Doctor doctor = doctor();
        call(null, "").andExpect(status().isUnauthorized());
        for (Actor actor : new Actor[] {patient(), staff(Role.ADMIN), staff(Role.RECEPTION), staff(Role.CASHIER)}) {
            call(actor, "").andExpect(status().isForbidden());
        }
        // 의사 역할이지만 의사 정보가 없는 계정도 거절한다.
        jdbc.update("DELETE FROM doctor_schedule WHERE doctor_id = ?", doctor.doctorId());
        jdbc.update("DELETE FROM doctor WHERE doctor_id = ?", doctor.doctorId());
        call(doctor.actor(), "").andExpect(status().isUnauthorized());
    }
}
