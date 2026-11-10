package com.databasesystem.hospital.prescription;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@Validated
public class PrescriptionController {
    private final PrescriptionService service;

    public PrescriptionController(PrescriptionService service) { this.service = service; }

    @GetMapping("/api/v1/doctor/medications")
    @QueryParameters("keyword")
    public Map<String, Object> medications(@AuthenticationPrincipal HospitalPrincipal user,
                                           @RequestParam String keyword) { return service.medications(user, keyword); }

    @PostMapping("/api/v1/doctor/visits/{visitId}/prescription-checks")
    public Map<String, Object> check(@AuthenticationPrincipal HospitalPrincipal user,
                                     @PathVariable @Positive long visitId, @Valid @RequestBody Check request) {
        return service.check(user, visitId, request.medicationIds());
    }

    @PostMapping("/api/v1/doctor/visits/{visitId}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> save(@AuthenticationPrincipal HospitalPrincipal user,
                                    @PathVariable @Positive long visitId, @Valid @RequestBody Save request) {
        return service.save(user, visitId, request);
    }

    @PostMapping("/api/v1/doctor/prescriptions/{prescriptionId}/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable @Positive long prescriptionId) { return service.cancel(user, prescriptionId); }

    public record Check(@NotEmpty List<@NotNull @Positive Long> medicationIds) { }
    public record Item(@NotNull @Positive Long medicationId,
                       @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 4, fraction = 2) BigDecimal dosePerIntake,
                       @NotBlank @Size(max = 10) String doseUnit,
                       @NotNull @Min(1) @Max(32767) Integer timesPerDay,
                       @NotNull @Min(1) @Max(32767) Integer intervalDays,
                       @NotNull @Min(1) @Max(32767) Integer durationDays,
                       @NotNull Timing timing, @NotBlank @Size(max = 200) String instructions) { }
    public enum Timing { BEFORE_MEAL, AFTER_MEAL, BEDTIME }
    public record Override(@NotBlank String warningId, String reason) { }
    public static class Save {
        @NotEmpty @Valid private List<@NotNull Item> items;
        @NotNull @Valid private List<@NotNull Override> overrides = List.of();
        public List<Item> getItems() { return items; }
        public void setItems(List<Item> items) { this.items = items; }
        public List<Override> getOverrides() { return overrides; }
        @JsonSetter(nulls = Nulls.FAIL)
        public void setOverrides(List<Override> overrides) { this.overrides = overrides; }
    }
}
