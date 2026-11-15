package com.databasesystem.hospital.patient;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PatientIntegrationTest extends IntegrationSupport {
    @Test
    void meReturnsOnlyTheAuthenticatedProfile() throws Exception {
        Actor patient = patient();
        getAs("/api/v1/me", patient).andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.patientId").value(patient.patientId()))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        getAs("/api/v1/me", staff(Role.CASHIER)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("CASHIER"));
    }

    @Test
    void patchPreservesMissingFieldsButRejectsNullAndDemographics() throws Exception {
        Actor patient = patient();
        mvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + patient.token())
                        .contentType("application/json").content("{\"name\":\"수정환자\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("수정환자"))
                .andExpect(jsonPath("$.phone").value("01012345678"));
        for (String body : new String[]{"{}", "{\"name\":null,\"phone\":\"01011112222\"}", "{\"gender\":\"F\"}"}) {
            mvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + patient.token())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void duplicateProfileUpdateRollsBackAndFamilyPhoneIsAllowed() throws Exception {
        Actor first = patient();
        Actor second = patient("다른환자", LocalDate.of(2018, 3, 22), "01012345678");
        mvc.perform(patch("/api/v1/me/profile").header("Authorization", "Bearer " + second.token())
                        .contentType("application/json").content("{\"name\":\"테스트환자\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PATIENT"));
        getAs("/api/v1/me", second).andExpect(jsonPath("$.name").value("다른환자"));
        getAs("/api/v1/staff/patients/" + first.patientId(), second).andExpect(status().isForbidden());
    }

    @Test
    void receptionistSearchMasksPhoneAndCorrectionIsAudited() throws Exception {
        Actor patient = patient();
        Actor receptionist = staff(Role.RECEPTION);
        getAs("/api/v1/staff/patients?name=테스트", receptionist).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].displayPhone").value("010-****-5678"))
                .andExpect(jsonPath("$.items[0].phone").doesNotExist());
        getAs("/api/v1/staff/patients", receptionist).andExpect(status().isBadRequest());
        getAs("/api/v1/staff/patients?unknown=value", receptionist).andExpect(status().isBadRequest());
        getAs("/api/v1/staff/patients/" + patient.patientId(), receptionist).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/staff/patients/" + patient.patientId() + "/demographics")
                        .header("Authorization", "Bearer " + receptionist.token()).contentType("application/json")
                        .content("{\"birthDate\":\"2018-03-23\",\"gender\":\"F\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gender").value("F"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patient_demographic_audit", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT changed_by_account_id FROM patient_demographic_audit", Long.class))
                .isEqualTo(receptionist.accountId());
    }

    @Test
    void passwordChangeInvalidatesTheOldTokenAndOldPassword() throws Exception {
        Actor patient = patient();
        mvc.perform(patch("/api/v1/me/password").header("Authorization", "Bearer " + patient.token())
                        .contentType("application/json").content(mapper.writeValueAsString(
                                Map.of("currentPassword", "wrong", "newPassword", "ChangedPass123!"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        mvc.perform(patch("/api/v1/me/password").header("Authorization", "Bearer " + patient.token())
                        .contentType("application/json").content(mapper.writeValueAsString(
                                Map.of("currentPassword", PASSWORD, "newPassword", "ChangedPass123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        getAs("/api/v1/me", patient).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(mapper.writeValueAsString(
                        Values.map("loginId", patient.loginId(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(mapper.writeValueAsString(
                        Values.map("loginId", patient.loginId(), "password", "ChangedPass123!"))))
                .andExpect(status().isOk());
    }
}
