package com.databasesystem.hospital.visit;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisitIntegrationTest extends IntegrationSupport {
    @Test
    void receptionistCanSearchCheckInAndCancelBothStates() throws Exception {
        Actor patient = patient();
        Actor reception = staff(Role.RECEPTION);
        Doctor doctor = doctor();
        long reservation = reservation(patient, doctor, NOW.plusSeconds(3600));
        getAs("/api/v1/reception/appointments?keyword=" + patient.patientId(), reception)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].appointmentId").value(reservation))
                .andExpect(jsonPath("$.items[0].visitId").isEmpty());
        String body = postAs("/api/v1/reception/appointments/" + reservation + "/check-in", reception, null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.appointmentStatus").value("CHECKED_IN"))
                .andExpect(jsonPath("$.visit.status").value("WAITING")).andReturn().getResponse().getContentAsString();
        long visit = mapper.readTree(body).path("visit").path("visitId").asLong();
        postAs("/api/v1/reception/appointments/" + reservation + "/check-in", reception, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
        postAs("/api/v1/reception/visits/" + visit + "/cancel", reception, Map.of("reason", "환자 요청"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appointmentStatus").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT reservation_status FROM reservation WHERE reservation_id = ?", String.class, reservation))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM visit WHERE visit_id = ?", String.class, visit)).isEqualTo("환자 요청");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Long.class)).isEqualTo(1);
    }

    @Test
    void queueIsScopedAndTheDateFilterCannotReadAnotherDay() throws Exception {
        Actor patient = patient();
        Doctor first = doctor();
        Doctor second = doctor();
        visit(patient, first, "WAITING");
        visit(patient, second, "WAITING");
        getAs("/api/v1/visits/queue", first.actor()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].doctorId").value(first.doctorId()));
        getAs("/api/v1/visits/queue?doctorId=" + second.doctorId(), first.actor()).andExpect(status().isForbidden());
        getAs("/api/v1/visits/queue", patient).andExpect(status().isForbidden());
        getAs("/api/v1/reception/appointments?date=2026-11-11", staff(Role.RECEPTION)).andExpect(status().isBadRequest());
    }

    @Test
    void queueOrderRecordAndCompletionRulesAreEnforced() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        long first = visit(patient, doctor, "WAITING");
        long second = visit(patient, doctor, "WAITING");
        postAs("/api/v1/doctor/visits/" + second + "/start", doctor.actor(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_QUEUE_HEAD"));
        postAs("/api/v1/doctor/visits/" + first + "/start", doctor.actor(), null).andExpect(status().isOk());
        postAs("/api/v1/doctor/visits/" + second + "/start", doctor.actor(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCTOR_ALREADY_IN_PROGRESS"));
        postAs("/api/v1/doctor/visits/" + first + "/complete", doctor.actor(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEDICAL_RECORD_REQUIRED"));
        Map<String, String> record = Map.of("symptoms", "증상", "diagnosis", "진단", "treatmentNote", "진료");
        postAs("/api/v1/doctor/visits/" + first + "/medical-record", doctor.actor(), record).andExpect(status().isCreated());
        postAs("/api/v1/doctor/visits/" + first + "/medical-record", doctor.actor(), record)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEDICAL_RECORD_EXISTS"));
        mvc.perform(patch("/api/v1/doctor/visits/" + first + "/medical-record")
                        .header("Authorization", "Bearer " + doctor.actor().token()).contentType("application/json")
                        .content("{\"diagnosis\":\"수정진단\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.symptoms").value("증상"));
        getAs("/api/v1/doctor/visits/" + first, doctor.actor()).andExpect(status().isOk())
                .andExpect(jsonPath("$.medicalRecord.diagnosis").value("수정진단"));
        postAs("/api/v1/doctor/visits/" + first + "/complete", doctor.actor(), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("UNPAID"));
        mvc.perform(patch("/api/v1/doctor/visits/" + first + "/medical-record")
                        .header("Authorization", "Bearer " + doctor.actor().token()).contentType("application/json")
                        .content("{\"diagnosis\":\"금지\"}"))
                .andExpect(status().isConflict());
        postAs("/api/v1/doctor/visits/" + second + "/start", doctor.actor(), null).andExpect(status().isOk());
    }

    @Test
    void otherDoctorCannotReadOrChangeTheVisitAndProgressCannotBeCancelled() throws Exception {
        Actor patient = patient();
        Doctor owner = doctor();
        Doctor other = doctor();
        long visit = visit(patient, owner, "IN_PROGRESS");
        getAs("/api/v1/doctor/visits/" + visit, other.actor()).andExpect(status().isNotFound());
        postAs("/api/v1/doctor/visits/" + visit + "/complete", other.actor(), null).andExpect(status().isNotFound());
        postAs("/api/v1/reception/visits/" + visit + "/cancel", staff(Role.RECEPTION), Map.of("reason", "취소 요청"))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentCheckInCreatesOnlyOneVisit() throws Exception {
        Actor patient = patient();
        Actor reception = staff(Role.RECEPTION);
        Doctor doctor = doctor();
        long reservation = reservation(patient, doctor, NOW.plusSeconds(3600));
        assertConcurrent("/api/v1/reception/appointments/" + reservation + "/check-in", reception, 201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentStartCannotCreateTwoProgressingVisits() throws Exception {
        Actor patient = patient();
        Doctor doctor = doctor();
        long visit = visit(patient, doctor, "WAITING");
        assertConcurrent("/api/v1/doctor/visits/" + visit + "/start", doctor.actor(), 200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE visit_status = 'IN_PROGRESS'", Long.class)).isEqualTo(1);
    }

    private void assertConcurrent(String path, Actor actor, int first, int second) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(1, 2).stream().map(i -> executor.submit(() -> {
                latch.await(); return postAs(path, actor, null).andReturn().getResponse().getStatus();
            })).toList();
            latch.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(first, second);
        }
    }
}
