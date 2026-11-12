package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import com.databasesystem.hospital.auth.Role;
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
@RequestMapping("/api/v1/admin/staff-accounts")
public class StaffAccountController {
    private final StaffAccountService service;

    public StaffAccountController(StaffAccountService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal HospitalPrincipal user,
                                      @Valid @RequestBody CreateRequest request) {
        return service.create(user, request);
    }

    @GetMapping
    @QueryParameters({"role", "active"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam(required = false) Role role,
                                    @RequestParam(required = false) Boolean active) {
        return service.list(user, role, active);
    }

    @GetMapping("/{accountId}")
    public Map<String, Object> get(@AuthenticationPrincipal HospitalPrincipal user, @PathVariable long accountId) {
        return service.get(user, accountId);
    }

    @PatchMapping("/{accountId}")
    public Map<String, Object> update(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable long accountId,
                                      @Valid @RequestBody UpdateRequest request) {
        return service.update(user, accountId, request);
    }

    /** 보낸 필드만 수정한다. loginId·password 등 정의되지 않은 필드는 JSON 파서가 400으로 거절한다. */
    public record UpdateRequest(String name, Role role, Boolean active) { }

    public record CreateRequest(@NotBlank String loginId,
                                @NotBlank @Size(min = 8, max = 72) String initialPassword,
                                @NotBlank String name,
                                @NotNull Role role,
                                @NotNull Boolean active) { }
}
