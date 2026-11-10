package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationIntegrationTest extends IntegrationSupport {
    private Map<String, Object> slot(Doctor doctor, String time) {
        return Map.of("scheduleId", doctor.scheduleId(), "startsAt", "2026-11-10T" + time + ":00+09:00");
    }

    @Test
    void createListDetailAndCancelReleaseTheSlot() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        String body = postAs("/api/v1/appointments", patient, slot(doctor, "10:00"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RESERVED"))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(body).path("appointmentId").asLong();
        getAs("/api/v1/appointments?period=UPCOMING", patient).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        getAs("/api/v1/appointments/" + id, patient).andExpect(jsonPath("$.canCancel").value(true));
        postAs("/api/v1/appointments/" + id + "/cancel", patient, null).andExpect(status().isOk());
        postAs("/api/v1/appointments", patient, slot(doctor, "10:00")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation", Long.class)).isEqualTo(2);
    }

    @Test
    void rejectsLunchClosingPastAndClosedSlots() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        for (String time : List.of("08:30", "12:30", "13:00", "16:30", "17:00", "10:15")) {
            postAs("/api/v1/appointments", patient, slot(doctor, time)).andExpect(status().isConflict());
        }
        postAs("/api/v1/appointments", patient, slot(doctor, "16:00")).andExpect(status().isCreated());
        jdbc.update("UPDATE doctor_schedule SET is_closed = TRUE WHERE schedule_id = ?", doctor.scheduleId());
        postAs("/api/v1/appointments", patient, slot(doctor, "10:00")).andExpect(status().isConflict());
    }

    @Test
    void anotherPatientGets404AndStaffCannotUsePatientRoutes() throws Exception {
        Actor first = patient();
        Actor second = patient("다른환자", LocalDate.of(2000, 1, 1), "01099998888");
        Doctor doctor = doctor();
        long id = reservation(first, doctor, NOW.plusSeconds(3600));
        getAs("/api/v1/appointments/" + id, second).andExpect(status().isNotFound());
        postAs("/api/v1/appointments/" + id + "/cancel", second, null).andExpect(status().isNotFound());
        getAs("/api/v1/appointments", staff(Role.RECEPTION)).andExpect(status().isForbidden());
    }

    @Test
    void rescheduleIsAtomicWhenNewSlotIsTaken() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        long old = reservation(patient, doctor, NOW.plusSeconds(3600));
        reservation(patient, doctor, NOW.plusSeconds(5400));
        postAs("/api/v1/appointments/" + old + "/reschedule", patient, slot(doctor, "10:30"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM reservation WHERE reservation_id = ?", String.class, old))
                .isEqualTo("RESERVED");
        postAs("/api/v1/appointments/" + old + "/reschedule", patient, slot(doctor, "11:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.previousAppointmentId").value(old));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE previous_reservation_id = ?", Long.class, old))
                .isEqualTo(1);
    }

    @Test
    void checkedInReservationsCannotBeChangedAndBadFiltersAreRejected() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        long id = reservation(patient, doctor, NOW.plusSeconds(3600));
        jdbc.update("UPDATE reservation SET reservation_status = 'CHECKED_IN' WHERE reservation_id = ?", id);
        postAs("/api/v1/appointments/" + id + "/cancel", patient, null).andExpect(status().isConflict());
        getAs("/api/v1/appointments?from=2026-11-11&to=2026-11-10", patient).andExpect(status().isBadRequest());
        getAs("/api/v1/appointments?status=BOOKED", patient).andExpect(status().isBadRequest());
        getAs("/api/v1/appointments?unexpected=true", patient).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRequestsCannotBookTheSameSlot() throws Exception {
        Actor first = patient();
        Actor second = patient("동시환자", LocalDate.of(2001, 1, 1), "01077776666");
        Doctor doctor = doctor();
        CountDownLatch ready = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = List.of(first, second).stream().map(actor -> executor.submit(() -> {
                ready.await();
                return postAs("/api/v1/appointments", actor, slot(doctor, "10:00"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            ready.countDown();
            assertThat(List.of(requests.get(0).get(10, TimeUnit.SECONDS), requests.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation", Long.class)).isEqualTo(1);
    }
}
