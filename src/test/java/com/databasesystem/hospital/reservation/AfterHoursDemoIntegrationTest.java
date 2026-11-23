package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = "hospital.demo.relaxed-hours=true")
class AfterHoursDemoIntegrationTest extends IntegrationSupport {
    @MockitoBean(name = "testClock") Clock lateClock;

    @BeforeEach void afterClosing() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-10T09:30:00Z"), ZoneOffset.UTC);
        when(lateClock.instant()).thenReturn(fixed.instant());
        when(lateClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(lateClock.withZone(any(ZoneId.class))).thenAnswer(i -> fixed.withZone(i.getArgument(0)));
    }

    @Test void canBookChangeAndCheckInTodayAfterClosing() throws Exception {
        Actor patient = patient();
        Actor desk = staff(Role.RECEPTION);
        Doctor doctor = doctor();
        getAs("/api/v1/doctors/" + doctor.doctorId() + "/availability?from=2026-11-10&to=2026-11-10", patient)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].slots[0]").value("2026-11-10T09:00:00+09:00"));
        var created = postAs("/api/v1/appointments", patient,
                Map.of("scheduleId", doctor.scheduleId(), "startsAt", "2026-11-10T15:00:00+09:00"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.canCancel").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(created).path("appointmentId").asLong();
        getAs("/api/v1/appointments?period=UPCOMING", patient)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        postAs("/api/v1/appointments/" + id + "/cancel", patient, null).andExpect(status().isOk());
        long checkInId = mapper.readTree(postAs("/api/v1/appointments", patient,
                Map.of("scheduleId", doctor.scheduleId(), "startsAt", "2026-11-10T16:00:00+09:00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("appointmentId").asLong();
        postAs("/api/v1/reception/appointments/" + checkInId + "/check-in", desk, null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.visit.status").value("WAITING"));
        postAs("/api/v1/reception/appointments/" + checkInId + "/check-in", desk, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"));
    }
}
