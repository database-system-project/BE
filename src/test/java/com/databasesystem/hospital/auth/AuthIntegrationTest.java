package com.databasesystem.hospital.auth;

import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationSupport {
    @Autowired private JwtEncoder encoder;

    private Map<String, Object> signup(String login) {
        return Values.map("loginId", login, "password", PASSWORD, "name", "홍길동",
                "birthDate", "2018-03-22", "gender", "M", "phone", "010-1234-5678", "privacyAgreed", true);
    }

    @Test
    void signupAndLoginIssueARealVerifiedBearerToken() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json")
                        .content(mapper.writeValueAsString(signup("patient-demo"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.patientId").value(100001))
                .andExpect(jsonPath("$.role").value("PATIENT"));
        String body = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("loginId", "patient-demo", "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(body).path("accessToken").asText();
        mvc.perform(get("/api/v1/nonexistent").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        String hash = jdbc.queryForObject("SELECT password_hash FROM user_account", String.class);
        assertThat(hash).doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void duplicatePatientRollsBackTheNewAccount() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json")
                .content(mapper.writeValueAsString(signup("first")))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json")
                        .content(mapper.writeValueAsString(signup("second"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PATIENT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account", Long.class)).isEqualTo(1);
        mvc.perform(post("/api/v1/auth/signup").contentType("application/json")
                        .content(mapper.writeValueAsString(signup("first"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOGIN_ID_TAKEN"));
    }

    @Test
    void consentFutureBirthAndUnexpectedFieldsAreRejected() throws Exception {
        for (Map<String, Object> invalid : List.of(signup("consent"), signup("future"), signup("role"))) {
            switch ((String) invalid.get("loginId")) {
                case "consent" -> invalid.put("privacyAgreed", false);
                case "future" -> invalid.put("birthDate", "2027-01-01");
                default -> invalid.put("role", "ADMIN");
            }
            mvc.perform(post("/api/v1/auth/signup").contentType("application/json")
                    .content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_account", Long.class)).isZero();
    }

    @Test
    void inactiveAccountCannotLoginOrUseAnExistingToken() throws Exception {
        Actor patient = patient();
        jdbc.update("UPDATE user_account SET account_status = 'INACTIVE' WHERE account_id = ?", patient.accountId());
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("loginId", patient.loginId(), "password", PASSWORD))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        getAs("/api/v1/nonexistent", patient).andExpect(status().isUnauthorized());
    }

    @Test
    void missingTamperedAndExpiredTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/nonexistent")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/nonexistent").header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
        Actor patient = patient();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(TokenService.ISSUER)
                .subject(Long.toString(patient.accountId())).claim("ver", 0)
                .issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.minusSeconds(1)).build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        mvc.perform(get("/api/v1/nonexistent").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }
}
