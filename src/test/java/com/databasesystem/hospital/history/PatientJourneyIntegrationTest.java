package com.databasesystem.hospital.history;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIf("domainsAvailable")
class PatientJourneyIntegrationTest extends IntegrationSupport {
    static boolean domainsAvailable() {
        return List.of("patient.PatientController", "reservation.ReservationController", "visit.VisitController",
                "prescription.PrescriptionController", "billing.BillingController").stream()
                .allMatch(name -> ClassUtils.isPresent("com.databasesystem.hospital." + name,
                        PatientJourneyIntegrationTest.class.getClassLoader()));
    }

    @Test
    void signupThroughReceiptAndPatientHistoryUsesTheSameSavedData() throws Exception {
        Doctor doctor = doctor(); Actor reception = staff(Role.RECEPTION), cashier = staff(Role.CASHIER), admin = staff(Role.ADMIN);
        long medicine = medication("연계 검증약"), fee = fee("FIRST_VISIT", 18000, NOW.minusSeconds(3600), admin);
        jdbc.update("INSERT INTO age_contraindication VALUES (?, 1, 12, 'LT', '연계 연령 경고')", medicine);
        String signup = postAs("/api/v1/auth/signup", reception, Map.of("loginId", "journey", "password", PASSWORD,
                        "name", "연계환자", "birthDate", "2018-03-22", "gender", "M", "phone", "01012345678", "privacyAgreed", true))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String login = postAs("/api/v1/auth/login", reception, Map.of("loginId", "journey", "password", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Actor patient = new Actor(mapper.readTree(signup).path("accountId").asLong(), mapper.readTree(signup).path("patientId").asLong(),
                null, null, "journey", mapper.readTree(login).path("accessToken").asText());
        getAs("/api/v1/me", patient).andExpect(status().isOk()).andExpect(jsonPath("$.profile.patientId").value(patient.patientId()));
        String reservation = postAs("/api/v1/appointments", patient, Map.of("scheduleId", doctor.scheduleId(), "startsAt", "2026-11-10T09:30:00+09:00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long appointment = mapper.readTree(reservation).path("appointmentId").asLong();
        getAs("/api/v1/reception/appointments?keyword=" + patient.patientId(), reception).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].appointmentId").value(appointment));
        String checkIn = postAs("/api/v1/reception/appointments/" + appointment + "/check-in", reception, null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long visit = mapper.readTree(checkIn).path("visit").path("visitId").asLong();
        String path = "/api/v1/doctor/visits/" + visit;
        postAs(path + "/start", doctor.actor(), null).andExpect(status().isOk());
        getAs("/api/v1/appointments/" + appointment, patient).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CHECKED_IN"));
        Map<String, Object> item = Map.of("medicationId", medicine, "dosePerIntake", 1.25, "doseUnit", "정", "timesPerDay", 2,
                "intervalDays", 1, "durationDays", 3, "timing", "AFTER_MEAL", "instructions", "개인 복용 지시");
        postAs(path + "/prescriptions", doctor.actor(), Map.of("items", List.of(item))).andExpect(status().isConflict());
        String checks = postAs(path + "/prescription-checks", doctor.actor(), Map.of("medicationIds", List.of(medicine)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String warning = mapper.readTree(checks).path("warnings").get(0).path("warningId").asText();
        String saved = postAs(path + "/prescriptions", doctor.actor(), Map.of("items", List.of(item), "overrides",
                        List.of(Map.of("warningId", warning, "reason", "의사 확인 사유"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long prescription = mapper.readTree(saved).path("prescriptionId").asLong();
        postAs(path + "/medical-record", doctor.actor(), Map.of("symptoms", "기침", "diagnosis", "연계 진단", "treatmentNote", "연계 진료"))
                .andExpect(status().isCreated());
        postAs(path + "/complete", doctor.actor(), null).andExpect(status().isOk());
        getAs("/api/v1/me/visits/" + visit, patient).andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.medicalRecord.diagnosis").value("연계 진단"));
        getAs("/api/v1/billing/visits/" + visit, cashier).andExpect(status().isOk()).andExpect(jsonPath("$.feeId").value(fee));
        String paid = postAs("/api/v1/billing/visits/" + visit + "/payments", cashier, Map.of("feeId", fee))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long payment = mapper.readTree(paid).path("paymentId").asLong();
        getAs("/api/v1/payments/" + payment + "/receipt", patient).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(18000));
        getAs("/api/v1/billing/visits/" + visit + "/prescription-print", cashier).andExpect(status().isOk())
                .andExpect(jsonPath("$.prescriptions[0].prescriptionId").value(prescription));
        getAs("/api/v1/me/prescriptions/" + prescription, patient).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].instructions").value("개인 복용 지시")).andExpect(jsonPath("$.items[0].dosePerIntake").value(1.25));
        getAs("/api/v1/me/visits", patient).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].paymentId").value(payment));
    }
}
