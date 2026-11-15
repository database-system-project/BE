package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/v1/admin/doctors")
public class AdminDoctorController {
    private final AdminDoctorService service;

    public AdminDoctorController(AdminDoctorService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal HospitalPrincipal user,
                                      @Valid @RequestBody CreateRequest request) {
        return service.create(user, request);
    }

    @GetMapping
    @QueryParameters({"departmentId", "keyword", "active"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam(required = false) Long departmentId,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Boolean active) {
        return service.list(user, departmentId, keyword, active);
    }

    @GetMapping("/{doctorId}")
    public Map<String, Object> get(@AuthenticationPrincipal HospitalPrincipal user, @PathVariable long doctorId) {
        return service.get(user, doctorId);
    }

    @PatchMapping("/{doctorId}")
    public Map<String, Object> update(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable long doctorId, @RequestBody JsonNode body) {
        return service.update(user, doctorId, body);
    }

    public record CreateRequest(Long accountId, String loginId, @Size(min = 8, max = 72) String initialPassword,
                                @NotNull Long departmentId, @NotBlank String name, @NotNull Boolean active,
                                Boolean accountActive) { }
}
