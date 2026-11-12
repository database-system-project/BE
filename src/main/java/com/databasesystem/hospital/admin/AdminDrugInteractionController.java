package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/api/v1/admin/drug-interactions")
public class AdminDrugInteractionController {
    private final AdminDrugInteractionService service;

    public AdminDrugInteractionController(AdminDrugInteractionService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal HospitalPrincipal user,
                                      @Valid @RequestBody CreateRequest request) {
        return service.create(user, request);
    }

    @GetMapping
    @QueryParameters({"medicationId"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam Map<String, String> query) {
        return service.list(user, query);
    }

    @GetMapping("/{medicationAId}/{medicationBId}")
    public Map<String, Object> get(@AuthenticationPrincipal HospitalPrincipal user,
                                   @PathVariable long medicationAId, @PathVariable long medicationBId) {
        return service.get(user, medicationAId, medicationBId);
    }

    @PatchMapping("/{medicationAId}/{medicationBId}")
    public Map<String, Object> update(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable long medicationAId, @PathVariable long medicationBId,
                                      @RequestBody JsonNode body) {
        return service.update(user, medicationAId, medicationBId, body);
    }

    public record CreateRequest(@NotNull @Positive Long medicationAId, @NotNull @Positive Long medicationBId,
                                @NotBlank String message) { }
}
