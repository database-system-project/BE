package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/admin/age-contraindications")
public class AdminAgeContraindicationController {
    private final AdminAgeContraindicationService service;

    public AdminAgeContraindicationController(AdminAgeContraindicationService service) { this.service = service; }

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

    @GetMapping("/{medicationId}/{ageSeq}")
    public Map<String, Object> get(@AuthenticationPrincipal HospitalPrincipal user,
                                   @PathVariable long medicationId, @PathVariable long ageSeq) {
        return service.get(user, medicationId, ageSeq);
    }

    @PatchMapping("/{medicationId}/{ageSeq}")
    public Map<String, Object> update(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable long medicationId, @PathVariable long ageSeq,
                                      @RequestBody JsonNode body) {
        return service.update(user, medicationId, ageSeq, body);
    }

    /** LT는 미만, LTE는 이하다. 이 두 값 외에는 JSON 파서가 400으로 거절한다. */
    public enum AgeOperator { LT, LTE }

    public record CreateRequest(@NotNull @Positive Long medicationId, @NotNull @Min(0) @Max(32767) Integer ageLimit,
                                @NotNull AgeOperator ageOperator, @NotBlank String message) { }
}
