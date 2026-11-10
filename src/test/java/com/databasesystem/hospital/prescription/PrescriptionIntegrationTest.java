package com.databasesystem.hospital.prescription;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrescriptionIntegrationTest extends IntegrationSupport {
    @Test
    void changedRulesInvalidateWarningsAndSuccessfulOverridesKeepSnapshots() throws Exception {
        Actor patient = patient(); Doctor doctor = doctor(); long visit = visit(patient, doctor, "IN_PROGRESS");
        long medicine = medication("테스트약");
        jdbc.update("INSERT INTO age_contraindication VALUES (?, 1, 12, 'LT', '최초 경고')", medicine);
        String path = "/api/v1/doctor/visits/" + visit;
        String first = warning(path, doctor.actor(), medicine);
        assertThat(warning(path, doctor.actor(), medicine)).isEqualTo(first);
        jdbc.update("UPDATE age_contraindication SET age_content = '변경된 경고'");
        postAs(path + "/prescriptions", doctor.actor(), Map.of("items", List.of(item(medicine)), "overrides",
                        List.of(Map.of("warningId", first, "reason", "진료 판단"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUR_OVERRIDE_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription", Long.class)).isZero();
        String latest = warning(path, doctor.actor(), medicine);
        assertThat(latest).isNotEqualTo(first);
        String response = postAs(path + "/prescriptions", doctor.actor(), Map.of("items", List.of(item(medicine)), "overrides",
                        List.of(Map.of("warningId", latest, "reason", "진료 판단"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.warningCount").value(1))
                .andReturn().getResponse().getContentAsString();
        long prescription = mapper.readTree(response).path("prescriptionId").asLong();
        jdbc.update("UPDATE age_contraindication SET age_content = '다시 변경'");
        assertThat(jdbc.queryForObject("SELECT contra_content FROM contraindication_override", String.class)).isEqualTo("변경된 경고");
        postAs("/api/v1/doctor/prescriptions/" + prescription + "/cancel", doctor.actor(), null).andExpect(status().isOk());
        postAs("/api/v1/doctor/prescriptions/" + prescription + "/cancel", doctor.actor(), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PRESCRIPTION_STATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription_item", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contraindication_override", Long.class)).isEqualTo(1);
    }

    @Test
    void birthdayBoundaryUsesAgeAndLtLteSeparately() throws Exception {
        Actor patient = patient("생일환자", LocalDate.of(2018, 11, 10), "01011112222");
        Doctor doctor = doctor(); long visit = visit(patient, doctor, "IN_PROGRESS"); long medicine = medication("연령약");
        jdbc.update("INSERT INTO age_contraindication VALUES (?, 1, 8, 'LT', '미만'), (?, 2, 8, 'LTE', '이하')", medicine, medicine);
        postAs("/api/v1/doctor/visits/" + visit + "/prescription-checks", doctor.actor(), Map.of("medicationIds", List.of(medicine)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings.length()").value(1))
                .andExpect(jsonPath("$.warnings[0].ruleKey.ageSeq").value(2));
    }

    @Test
    void currentMedicationIncludesSameVisitAndExcludesCancelledOrExpired() throws Exception {
        Actor patient = patient(); Doctor doctor = doctor(); long visit = visit(patient, doctor, "IN_PROGRESS");
        long a = medication("약A"), b = medication("약B");
        jdbc.update("INSERT INTO drug_interaction VALUES (?, ?, '병용 경고')", a, b);
        String path = "/api/v1/doctor/visits/" + visit;
        postAs(path + "/prescriptions", doctor.actor(), Map.of("items", List.of(item(a)))).andExpect(status().isCreated());
        postAs(path + "/prescription-checks", doctor.actor(), Map.of("medicationIds", List.of(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings[0].type").value("INTERACTION"));
        jdbc.update("UPDATE prescription SET prescribed_at = ?", time(NOW.minusSeconds(3 * 86400)));
        postAs(path + "/prescription-checks", doctor.actor(), Map.of("medicationIds", List.of(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings").isEmpty());
        jdbc.update("UPDATE prescription SET prescribed_at = ?, prescription_status = 'CANCELLED'", time(NOW));
        postAs(path + "/prescription-checks", doctor.actor(), Map.of("medicationIds", List.of(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void invalidInputsAndOtherDoctorsCannotWriteAndSearchRetainsNulls() throws Exception {
        Actor patient = patient(); Doctor owner = doctor(); Doctor other = doctor(); long visit = visit(patient, owner, "IN_PROGRESS");
        long medicine = medication("검색약");
        getAs("/api/v1/doctor/medications?keyword=검색", owner.actor()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].efficacy").isEmpty());
        getAs("/api/v1/doctor/medications?keyword=검색", staff(Role.CASHIER)).andExpect(status().isForbidden());
        String path = "/api/v1/doctor/visits/" + visit;
        postAs(path + "/prescriptions", other.actor(), Map.of("items", List.of(item(medicine)))).andExpect(status().isNotFound());
        postAs(path + "/prescriptions", owner.actor(), Map.of("items", List.of(item(medicine), item(medicine))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MEDICATIONS"));
        var invalid = new java.util.HashMap<>(item(medicine)); invalid.put("dosePerIntake", 1.001);
        postAs(path + "/prescriptions", owner.actor(), Map.of("items", List.of(invalid))).andExpect(status().isBadRequest());
        var nullOverrides = new java.util.HashMap<String, Object>(); nullOverrides.put("items", List.of(item(medicine))); nullOverrides.put("overrides", null);
        postAs(path + "/prescriptions", owner.actor(), nullOverrides).andExpect(status().isBadRequest());
        jdbc.update("UPDATE medicine SET medicine_active = false");
        postAs(path + "/prescriptions", owner.actor(), Map.of("items", List.of(item(medicine))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MEDICATIONS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription", Long.class)).isZero();
    }

    @Test
    void simultaneousPrescriptionsForOnePatientCannotMissEachOther() throws Exception {
        Actor patient = patient(); Doctor first = doctor(), second = doctor();
        long firstVisit = visit(patient, first, "IN_PROGRESS"), secondVisit = visit(patient, second, "IN_PROGRESS");
        long a = medication("약A"), b = medication("약B");
        jdbc.update("INSERT INTO drug_interaction VALUES (?, ?, '병용 경고')", a, b);
        CountDownLatch latch = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { latch.await(); return postAs("/api/v1/doctor/visits/" + firstVisit + "/prescriptions", first.actor(),
                    Map.of("items", List.of(item(a)))).andReturn().getResponse().getStatus(); });
            var two = executor.submit(() -> { latch.await(); return postAs("/api/v1/doctor/visits/" + secondVisit + "/prescriptions", second.actor(),
                    Map.of("items", List.of(item(b)))).andReturn().getResponse().getStatus(); });
            latch.countDown();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription", Long.class)).isEqualTo(1);
    }

    private Map<String, Object> item(long medicine) {
        return Map.of("medicationId", medicine, "dosePerIntake", 1, "doseUnit", "정", "timesPerDay", 2,
                "intervalDays", 1, "durationDays", 3, "timing", "AFTER_MEAL", "instructions", "복용 안내");
    }

    private String warning(String path, Actor doctor, long medicine) throws Exception {
        String json = postAs(path + "/prescription-checks", doctor, Map.of("medicationIds", List.of(medicine)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("warnings").get(0).path("warningId").asText();
    }
}
