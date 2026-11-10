package com.databasesystem.hospital.common;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestBoundaryIntegrationTest extends IntegrationSupport {
    @Test
    void signupAndLoginValidateLengthsAfterStrippingOuterWhitespace() throws Exception {
        String login = "a".repeat(50), name = "나".repeat(50);
        Actor caller = staff(Role.RECEPTION);
        postAs("/api/v1/auth/signup", caller, signup("  " + login + "  ", "  " + name + "  ", "2018-03-22"))
                .andExpect(status().isCreated());
        postAs("/api/v1/auth/login", caller, Map.of("loginId", "  " + login + "  ", "password", PASSWORD))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT patient_name FROM patient", String.class)).isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account WHERE login_id = ?", Long.class, login)).isEqualTo(1);
        postAs("/api/v1/auth/signup", caller, signup("b".repeat(51), "정상 이름", "2018-03-22"))
                .andExpect(status().isBadRequest());
        postAs("/api/v1/auth/signup", caller, signup("other", "나".repeat(51), "2018-03-22"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancellationReasonUsesItsTrimmedLength() throws Exception {
        Actor patient = patient(), reception = staff(Role.RECEPTION);
        long visit = visit(patient, doctor(), "WAITING");
        postAs("/api/v1/reception/visits/" + visit + "/cancel", reception, Map.of("reason", "  " + "사".repeat(200) + "  "))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM visit WHERE visit_id = ?", String.class, visit)).isEqualTo("사".repeat(200));
    }

    @Test
    void invalidAndOutOfRangeQueryDatesReturn400InsteadOfServerErrors() throws Exception {
        Actor patient = patient();
        for (String date : List.of("+999999999-12-31", "+10000-01-01", "0000-01-01", "2026-02-30", "2026-1-01")) {
            mvc.perform(get("/api/v1/me/visits").param("to", date).header("Authorization", "Bearer " + patient.token()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        getAs("/api/v1/me/visits?to=9999-12-31", patient).andExpect(status().isOk());
    }

    @Test
    void birthDateRequiresAValidCalendarDateStringAndCorrectionUsesTheSameRules() throws Exception {
        Actor reception = staff(Role.RECEPTION);
        for (Object date : List.of(List.of(2018, 3, 22), "0000-01-01", "-0001-01-01", "2018-02-30")) {
            postAs("/api/v1/auth/signup", reception, signup("invalid", "잘못된 날짜", date))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient", Long.class)).isZero();
        Actor patient = patient();
        mvc.perform(patch("/api/v1/staff/patients/" + patient.patientId() + "/demographics")
                        .header("Authorization", "Bearer " + reception.token()).contentType("application/json")
                        .content("{\"birthDate\":\"0000-01-01\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient_demographic_audit", Long.class)).isZero();
    }

    private Map<String, Object> signup(String login, String name, Object birthDate) {
        return Map.of("loginId", login, "password", PASSWORD, "name", name, "birthDate", birthDate,
                "gender", "M", "phone", "01012345678", "privacyAgreed", true);
    }
}
