package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 현재 시각은 2026-11-10 09:00(한국 시간)으로 고정되어 있다. */
class CatalogIntegrationTest extends IntegrationSupport {
    private ResultActions call(Actor actor, String path) throws Exception {
        var request = get(path);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private long department(String name, boolean active) {
        return jdbc.queryForObject("INSERT INTO department(department_name, department_active) VALUES (?, ?) RETURNING department_id",
                Long.class, name, active);
    }

    /** 활성 의사 계정·직원을 가진 의사를 만들고 필요하면 비활성 상태로 바꾼다. */
    private long doctorIn(long department, String name, boolean doctorActive, boolean accountActive, boolean staffActive) {
        Actor employee = staff(Role.DOCTOR);
        long doctor = jdbc.queryForObject("""
                INSERT INTO doctor(account_id, department_id, doctor_name, doctor_active) VALUES (?, ?, ?, ?) RETURNING doctor_id
                """, Long.class, employee.accountId(), department, name, doctorActive);
        if (!accountActive) { jdbc.update("UPDATE user_account SET account_status = 'INACTIVE' WHERE account_id = ?", employee.accountId()); }
        if (!staffActive) { jdbc.update("UPDATE staff SET staff_active = FALSE WHERE account_id = ?", employee.accountId()); }
        return doctor;
    }

    private long schedule(long doctor, String date, boolean closed) {
        return jdbc.queryForObject("INSERT INTO doctor_schedule(doctor_id, schedule_date, is_closed) VALUES (?, ?::date, ?) RETURNING schedule_id",
                Long.class, doctor, date, closed);
    }

    private List<String> daySlots(String date) {
        List<String> times = List.of("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00",
                "13:30", "14:00", "14:30", "15:00", "15:30", "16:00");
        return times.stream().map(time -> date + "T" + time + ":00+09:00").toList();
    }

    private List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return mapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // ---- GET /api/v1/departments

    @Test
    void departmentsListsOnlyActiveOnesInNameOrder() throws Exception {
        Actor patient = patient();
        assertThat(body(call(patient, "/api/v1/departments")).get("items")).isEmpty();
        long bravo = department("Bravo", true);
        long alpha = department("Alpha", true);
        department("Charlie", false);
        JsonNode items = body(call(patient, "/api/v1/departments")).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("departmentId").asLong()).isEqualTo(alpha);
        assertThat(items.get(0).get("name").asText()).isEqualTo("Alpha");
        assertThat(items.get(1).get("departmentId").asLong()).isEqualTo(bravo);
        assertThat(items.get(0).size()).isEqualTo(2);
    }

    @Test
    void departmentsRejectsQueryParametersAndOtherRoles() throws Exception {
        call(patient(), "/api/v1/departments?active=true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        call(null, "/api/v1/departments").andExpect(status().isUnauthorized());
        for (Role role : List.of(Role.ADMIN, Role.DOCTOR, Role.RECEPTION, Role.CASHIER)) {
            call(staff(role), "/api/v1/departments").andExpect(status().isForbidden());
        }
    }

    // ---- GET /api/v1/departments/{departmentId}/doctors

    @Test
    void doctorsListsOnlyActiveDoctorsOfTheDepartmentInNameOrder() throws Exception {
        Actor patient = patient();
        long department = department("내과", true);
        long other = department("외과", true);
        long kim = doctorIn(department, "Kim", true, true, true);
        long abe = doctorIn(department, "Abe", true, true, true);
        long abe2 = doctorIn(department, "Abe", true, true, true);
        doctorIn(department, "Inactive", false, true, true);
        doctorIn(department, "AccountOff", true, false, true);
        doctorIn(department, "StaffOff", true, true, false);
        doctorIn(other, "Elsewhere", true, true, true);

        JsonNode items = body(call(patient, "/api/v1/departments/" + department + "/doctors")).get("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("doctorId").asLong()).isEqualTo(abe);
        assertThat(items.get(1).get("doctorId").asLong()).isEqualTo(abe2);
        assertThat(items.get(2).get("doctorId").asLong()).isEqualTo(kim);
        assertThat(items.get(2).get("name").asText()).isEqualTo("Kim");
        assertThat(items.get(2).get("departmentId").asLong()).isEqualTo(department);
        long empty = department("소아과", true);
        assertThat(body(call(patient, "/api/v1/departments/" + empty + "/doctors")).get("items")).isEmpty();
    }

    @Test
    void doctorsRejectsMissingInactiveAndInvalidDepartmentsAndOtherRoles() throws Exception {
        Actor patient = patient();
        long inactive = department("폐지과", false);
        long active = department("내과", true);
        call(patient, "/api/v1/departments/" + inactive + "/doctors").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        call(patient, "/api/v1/departments/999999999/doctors").andExpect(status().isNotFound());
        call(patient, "/api/v1/departments/0/doctors").andExpect(status().isBadRequest());
        call(patient, "/api/v1/departments/abc/doctors").andExpect(status().isBadRequest());
        call(patient, "/api/v1/departments/" + active + "/doctors?x=1").andExpect(status().isBadRequest());
        call(null, "/api/v1/departments/" + active + "/doctors").andExpect(status().isUnauthorized());
        call(staff(Role.DOCTOR), "/api/v1/departments/" + active + "/doctors").andExpect(status().isForbidden());
        call(staff(Role.ADMIN), "/api/v1/departments/" + active + "/doctors").andExpect(status().isForbidden());
    }

    // ---- GET /api/v1/doctors/{doctorId}/availability

    @Test
    void availabilityReturnsAllThirtyMinuteSlotsExceptLunchAndAfterFourPm() throws Exception {
        Doctor doctor = doctor(); // 오늘(2026-11-10) 일정이 있다.
        JsonNode result = body(call(patient(), "/api/v1/doctors/" + doctor.doctorId() + "/availability?from=2026-11-10&to=2026-11-17"));
        assertThat(result.get("doctorId").asLong()).isEqualTo(doctor.doctorId());
        assertThat(result.get("items")).hasSize(1);
        JsonNode day = result.get("items").get(0);
        assertThat(day.get("date").asText()).isEqualTo("2026-11-10");
        assertThat(day.get("scheduleId").asLong()).isEqualTo(doctor.scheduleId());
        // 09:00은 현재 시각과 같아 아직 지난 시각이 아니다. 12:30·13:00(점심)과 16:30 이후는 없다.
        assertThat(texts(day.get("slots"))).containsExactlyElementsOf(daySlots("2026-11-10"));
    }

    @Test
    void availabilityExcludesOccupiedSlotsButReopensCancelledOnes() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        long reserved = reservation(patient, doctor, Instant.parse("2026-11-10T01:00:00Z")); // 10:00
        long checkedIn = reservation(patient, doctor, Instant.parse("2026-11-10T01:30:00Z")); // 10:30
        jdbc.update("UPDATE reservation SET reservation_status = 'CHECKED_IN' WHERE reservation_id = ?", checkedIn);
        long cancelled = reservation(patient, doctor, Instant.parse("2026-11-10T02:00:00Z")); // 11:00
        jdbc.update("UPDATE reservation SET reservation_status = 'CANCELLED', reservation_cancelled_at = now() WHERE reservation_id = ?", cancelled);

        JsonNode day = body(call(patient, "/api/v1/doctors/" + doctor.doctorId() + "/availability?from=2026-11-10&to=2026-11-10"))
                .get("items").get(0);
        List<String> slots = texts(day.get("slots"));
        assertThat(slots).doesNotContain("2026-11-10T10:00:00+09:00", "2026-11-10T10:30:00+09:00");
        assertThat(slots).contains("2026-11-10T09:30:00+09:00", "2026-11-10T11:00:00+09:00");
        assertThat(slots).hasSize(11);
        assertThat(reserved).isPositive();
    }

    @Test
    void availabilityHidesPastDaysClosedDaysAndFullyBookedDaysAndRespectsTheRange() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        long id = doctor.doctorId();
        schedule(id, "2026-11-09", false);   // 어제: 모든 시각이 지났다
        schedule(id, "2026-11-11", true);    // 휴진
        long open = schedule(id, "2026-11-12", false);
        long full = schedule(id, "2026-11-13", false);
        for (String time : List.of("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00",
                "13:30", "14:00", "14:30", "15:00", "15:30", "16:00")) {
            jdbc.update("INSERT INTO reservation(patient_id, schedule_id, planned_reception_at) VALUES (?, ?, (?::date + ?::time) AT TIME ZONE 'Asia/Seoul')",
                    patient.patientId(), full, "2026-11-13", time);
        }
        schedule(id, "2026-11-20", false);

        JsonNode items = body(call(patient, "/api/v1/doctors/" + id + "/availability?from=2026-11-09&to=2026-11-17")).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("date").asText()).isEqualTo("2026-11-10");
        assertThat(items.get(1).get("date").asText()).isEqualTo("2026-11-12");
        assertThat(items.get(1).get("scheduleId").asLong()).isEqualTo(open);
        assertThat(texts(items.get(1).get("slots"))).containsExactlyElementsOf(daySlots("2026-11-12"));

        assertThat(body(call(patient, "/api/v1/doctors/" + id + "/availability?from=2026-11-12&to=2026-11-12")).get("items")).hasSize(1);
        assertThat(body(call(patient, "/api/v1/doctors/" + id + "/availability?from=2026-11-14&to=2026-11-19")).get("items")).isEmpty();
    }

    @Test
    void availabilityOnlyUsesTheRequestedDoctorsSchedules() throws Exception {
        Actor patient = patient();
        Doctor first = doctor();
        Doctor second = doctor();
        schedule(second.doctorId(), "2026-11-12", false);
        JsonNode items = body(call(patient, "/api/v1/doctors/" + first.doctorId() + "/availability?from=2026-11-10&to=2026-11-30")).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("scheduleId").asLong()).isEqualTo(first.scheduleId());
    }

    @Test
    void availabilityIs404ForMissingOrInactiveDoctorsDepartmentsAndAccounts() throws Exception {
        Actor patient = patient();
        String query = "/availability?from=2026-11-10&to=2026-11-17";
        long department = department("내과", true);
        long inactiveDepartment = department("폐지과", false);
        long off = doctorIn(department, "비활성의사", false, true, true);
        long accountOff = doctorIn(department, "계정비활성", true, false, true);
        long staffOff = doctorIn(department, "직원비활성", true, true, false);
        long inDeadDepartment = doctorIn(inactiveDepartment, "폐지과의사", true, true, true);
        for (long id : List.of(off, accountOff, staffOff, inDeadDepartment, 999_999_999L)) {
            if (id != 999_999_999L) { schedule(id, "2026-11-12", false); }
            call(patient, "/api/v1/doctors/" + id + query).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void availabilityRejectsInvalidQueriesAndOtherRoles() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        String base = "/api/v1/doctors/" + doctor.doctorId() + "/availability";
        for (String query : List.of("", "?from=2026-11-10", "?to=2026-11-17", "?from=2026-11-17&to=2026-11-10",
                "?from=2026/11/10&to=2026-11-17", "?from=2026-13-01&to=2026-11-17", "?from=&to=2026-11-17",
                "?from=2026-11-10&to=2026-11-17&extra=1", "?from=2026-11-10&from=2026-11-11&to=2026-11-17")) {
            call(patient, base + query).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        call(patient, "/api/v1/doctors/0/availability?from=2026-11-10&to=2026-11-17").andExpect(status().isBadRequest());
        call(patient, "/api/v1/doctors/abc/availability?from=2026-11-10&to=2026-11-17").andExpect(status().isBadRequest());
        String ok = base + "?from=2026-11-10&to=2026-11-17";
        call(null, ok).andExpect(status().isUnauthorized());
        call(doctor.actor(), ok).andExpect(status().isForbidden());
        call(staff(Role.ADMIN), ok).andExpect(status().isForbidden());
        call(staff(Role.RECEPTION), ok).andExpect(status().isForbidden());
    }
}
