package com.databasesystem.hospital.common;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class AppointmentTimePolicyTest {
    private final Clock late = Clock.fixed(Instant.parse("2026-11-10T09:30:00Z"), ZoneOffset.UTC);

    @Test void normalModeStillRejectsPastSlotsAndAfterHoursCheckIn() {
        var policy = new AppointmentTimePolicy(late, false);
        assertThat(policy.receptionClosed()).isTrue();
        assertThat(policy.isPast(Instant.parse("2026-11-10T07:00:00Z"))).isTrue();
    }

    @Test void demoModeAllowsTodayButNeverPreviousDates() {
        var policy = new AppointmentTimePolicy(late, true);
        assertThat(policy.receptionClosed()).isFalse();
        assertThat(policy.isPast(Instant.parse("2026-11-10T07:00:00Z"))).isFalse();
        assertThat(policy.isPast(Instant.parse("2026-11-09T07:00:00Z"))).isTrue();
    }
}
