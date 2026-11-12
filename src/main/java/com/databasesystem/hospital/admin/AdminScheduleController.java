package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/schedules")
public class AdminScheduleController {
    private final AdminScheduleService service;

    public AdminScheduleController(AdminScheduleService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@AuthenticationPrincipal HospitalPrincipal user,
                                      @Valid @RequestBody CreateRequest request) {
        return service.create(user, request);
    }

    @GetMapping
    @QueryParameters({"doctorId", "from", "to"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam Map<String, String> query) {
        return service.list(user, query);
    }

    @GetMapping("/{scheduleId}")
    public Map<String, Object> get(@AuthenticationPrincipal HospitalPrincipal user, @PathVariable long scheduleId) {
        return service.get(user, scheduleId);
    }

    @PatchMapping("/{scheduleId}")
    public Map<String, Object> update(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable long scheduleId, @RequestBody JsonNode body) {
        return service.update(user, scheduleId, body);
    }

    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal HospitalPrincipal user, @PathVariable long scheduleId) {
        service.delete(user, scheduleId);
    }

    /** 운영시간 필드는 받지 않는다. 보내면 JSON 파서가 400으로 거절한다. */
    public record CreateRequest(@NotNull @Positive Long doctorId, @NotNull LocalDate date, @NotNull Boolean closed) { }
}
