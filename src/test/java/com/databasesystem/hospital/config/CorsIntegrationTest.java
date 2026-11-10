package com.databasesystem.hospital.config;

import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "hospital.web.cors-allowed-origins=http://localhost:5173")
class CorsIntegrationTest extends IntegrationSupport {
    @Test
    void allowedPreflightDoesNotRequireAToken() throws Exception {
        mvc.perform(options("/api/v1/me/profile").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void actualRequestsStillRequireAuthenticationAndHaveCorsHeaders() throws Exception {
        mvc.perform(get("/api/v1/me").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        Actor patient = patient();
        mvc.perform(get("/api/v1/me").header("Origin", "http://localhost:5173").header("Authorization", "Bearer " + patient.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile.patientId").value(patient.patientId()))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void otherOriginsAndUnapprovedHeadersAreRejected() throws Exception {
        mvc.perform(options("/api/v1/me").header("Origin", "https://unapproved.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/v1/me").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "X-Unapproved"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
