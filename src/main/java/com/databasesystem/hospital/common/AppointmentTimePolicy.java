package com.databasesystem.hospital.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Explicit demo option: relax today's time limits without changing the system clock. */
@Component
public class AppointmentTimePolicy {
    private final Clock clock;
    private final boolean relaxed;

    public AppointmentTimePolicy(Clock clock,
            @Value("${hospital.demo.relaxed-hours:false}") boolean relaxed) {
        this.clock = clock;
        this.relaxed = relaxed;
    }

    public Instant cutoff() {
        return relaxed ? LocalDate.now(clock.withZone(Values.SEOUL)).atStartOfDay(Values.SEOUL).toInstant()
                : clock.instant();
    }

    public boolean isPast(Instant slot) { return slot.isBefore(cutoff()); }

    public boolean receptionClosed() {
        return !relaxed && !LocalTime.now(clock.withZone(Values.SEOUL)).isBefore(LocalTime.of(17, 0));
    }
}
