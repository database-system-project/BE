package com.databasesystem.hospital.patient;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Validated
public class PatientController {
    private final PatientService service;

    public PatientController(PatientService service) { this.service = service; }

    @GetMapping("/api/v1/me")
    public Map<String, Object> me(@AuthenticationPrincipal HospitalPrincipal principal) { return service.me(principal); }

    @PatchMapping("/api/v1/me/profile")
    public Map<String, Object> profile(@AuthenticationPrincipal HospitalPrincipal principal,
                                       @RequestBody Map<String, Object> request) { return service.profile(principal, request); }

    @PatchMapping("/api/v1/me/password")
    public Map<String, Object> password(@AuthenticationPrincipal HospitalPrincipal principal,
                                        @Valid @RequestBody PasswordChange request) { return service.password(principal, request); }

    @GetMapping("/api/v1/staff/patients")
    @QueryParameters({"name", "birthDate", "phone"})
    public Map<String, Object> search(@AuthenticationPrincipal HospitalPrincipal principal,
                                      @RequestParam Map<String, String> query) { return service.search(principal, query); }

    @GetMapping("/api/v1/staff/patients/{patientId}")
    public Map<String, Object> detail(@AuthenticationPrincipal HospitalPrincipal principal,
                                      @PathVariable @Positive long patientId) { return service.detail(principal, patientId); }

    @PatchMapping("/api/v1/staff/patients/{patientId}/demographics")
    public Map<String, Object> demographics(@AuthenticationPrincipal HospitalPrincipal principal,
                                             @PathVariable @Positive long patientId,
                                             @RequestBody Map<String, Object> request) {
        return service.demographics(principal, patientId, request);
    }

    public record PasswordChange(@NotBlank @Size(max = 72) String currentPassword,
                                 @NotBlank @Size(min = 8, max = 72) String newPassword) { }
}
