package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/appointments")
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal HospitalPrincipal principal,
                                      @Valid @RequestBody Slot request) { return service.create(principal, request); }

    @GetMapping
    @QueryParameters({"period", "status", "from", "to"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal principal,
                                    @RequestParam Map<String, String> query) { return service.list(principal, query); }

    @GetMapping("/{appointmentId}")
    public Map<String, Object> detail(@AuthenticationPrincipal HospitalPrincipal principal,
                                      @PathVariable @Positive long appointmentId) { return service.detail(principal, appointmentId); }

    @PostMapping("/{appointmentId}/reschedule")
    public Map<String, Object> reschedule(@AuthenticationPrincipal HospitalPrincipal principal,
                                          @PathVariable @Positive long appointmentId,
                                          @Valid @RequestBody Slot request) { return service.reschedule(principal, appointmentId, request); }

    @PostMapping("/{appointmentId}/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal HospitalPrincipal principal,
                                      @PathVariable @Positive long appointmentId) { return service.cancel(principal, appointmentId); }

    public record Slot(@NotNull @Positive Long scheduleId, @NotNull OffsetDateTime startsAt) { }
}
