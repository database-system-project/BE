package com.databasesystem.hospital.billing;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Validated
public class BillingController {
    private final BillingService service;
    public BillingController(BillingService service) { this.service = service; }

    @GetMapping("/api/v1/billing/visits")
    @QueryParameters({"paymentStatus", "from", "to"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam Map<String, String> query) { return service.list(user, query); }

    @GetMapping("/api/v1/billing/visits/{visitId}")
    public Map<String, Object> detail(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable @Positive long visitId) { return service.detail(user, visitId); }

    @PostMapping("/api/v1/billing/visits/{visitId}/payments")
    public Map<String, Object> pay(@AuthenticationPrincipal HospitalPrincipal user,
                                   @PathVariable @Positive long visitId, @Valid @RequestBody Payment request) {
        return service.pay(user, visitId, request.feeId());
    }

    @GetMapping("/api/v1/payments/{paymentId}/receipt")
    public Map<String, Object> receipt(@AuthenticationPrincipal HospitalPrincipal user,
                                       @PathVariable @Positive long paymentId) { return service.receipt(user, paymentId); }

    @GetMapping("/api/v1/billing/visits/{visitId}/prescription-print")
    public Map<String, Object> print(@AuthenticationPrincipal HospitalPrincipal user,
                                     @PathVariable @Positive long visitId) { return service.print(user, visitId); }

    public record Payment(@NotNull @Positive Long feeId) { }
}
