package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/medications")
public class AdminMedicationController {
    private final AdminMedicationService service;

    public AdminMedicationController(AdminMedicationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal HospitalPrincipal user,
                                      @Valid @RequestBody CreateRequest request) {
        return service.create(user, request);
    }

    @GetMapping
    @QueryParameters({"keyword", "active"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Boolean active) {
        return service.list(user, keyword, active);
    }

    @GetMapping("/{medicationId}")
    public Map<String, Object> get(@AuthenticationPrincipal HospitalPrincipal user, @PathVariable long medicationId) {
        return service.get(user, medicationId);
    }

    @PatchMapping("/{medicationId}")
    public Map<String, Object> update(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable long medicationId, @RequestBody JsonNode body) {
        return service.update(user, medicationId, body);
    }

    /** 안내 문구 4개는 생략하거나 null이면 결측으로 저장한다. */
    public record CreateRequest(@NotBlank String itemSeq, @NotBlank String name, @NotBlank String companyName,
                                String efficacy, String usage, String precautions, String storage,
                                @NotNull Boolean active) { }
}
