package com.databasesystem.hospital.history;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Validated
public class HistoryController {
    private final HistoryService service;
    public HistoryController(HistoryService service) { this.service = service; }

    @GetMapping("/api/v1/me/visits")
    @QueryParameters({"from", "to"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam Map<String, String> query) { return service.list(user, query); }

    @GetMapping("/api/v1/me/visits/{visitId}")
    public Map<String, Object> detail(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable @Positive long visitId) { return service.detail(user, visitId); }

    @GetMapping("/api/v1/me/prescriptions/{prescriptionId}")
    public Map<String, Object> prescription(@AuthenticationPrincipal HospitalPrincipal user,
                                            @PathVariable @Positive long prescriptionId) { return service.prescription(user, prescriptionId); }
}
