package com.databasesystem.hospital.visit;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Validated
public class VisitController {
    private final VisitService service;

    public VisitController(VisitService service) { this.service = service; }

    @GetMapping("/api/v1/reception/appointments")
    @QueryParameters({"date", "keyword", "status"})
    public Map<String, Object> appointments(@AuthenticationPrincipal HospitalPrincipal user,
                                            @RequestParam Map<String, String> query) { return service.appointments(user, query); }

    @PostMapping("/api/v1/reception/appointments/{appointmentId}/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> checkIn(@AuthenticationPrincipal HospitalPrincipal user,
                                       @PathVariable @Positive long appointmentId) { return service.checkIn(user, appointmentId); }

    @GetMapping("/api/v1/visits/queue")
    @QueryParameters({"doctorId", "status"})
    public Map<String, Object> queue(@AuthenticationPrincipal HospitalPrincipal user,
                                     @RequestParam Map<String, String> query) { return service.queue(user, query); }

    @PostMapping("/api/v1/reception/visits/{visitId}/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable @Positive long visitId, @Valid @RequestBody Cancellation request) {
        return service.cancel(user, visitId, request.reason());
    }

    @GetMapping("/api/v1/doctor/visits/{visitId}")
    public Map<String, Object> detail(@AuthenticationPrincipal HospitalPrincipal user,
                                      @PathVariable @Positive long visitId) { return service.detail(user, visitId); }

    @PostMapping("/api/v1/doctor/visits/{visitId}/start")
    public Map<String, Object> start(@AuthenticationPrincipal HospitalPrincipal user,
                                     @PathVariable @Positive long visitId) { return service.start(user, visitId); }

    @PostMapping("/api/v1/doctor/visits/{visitId}/medical-record")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createRecord(@AuthenticationPrincipal HospitalPrincipal user,
                                            @PathVariable @Positive long visitId, @Valid @RequestBody MedicalRecord request) {
        return service.createRecord(user, visitId, request);
    }

    @PatchMapping("/api/v1/doctor/visits/{visitId}/medical-record")
    public Map<String, Object> updateRecord(@AuthenticationPrincipal HospitalPrincipal user,
                                            @PathVariable @Positive long visitId, @RequestBody Map<String, Object> request) {
        return service.updateRecord(user, visitId, request);
    }

    @PostMapping("/api/v1/doctor/visits/{visitId}/complete")
    public Map<String, Object> complete(@AuthenticationPrincipal HospitalPrincipal user,
                                        @PathVariable @Positive long visitId) { return service.complete(user, visitId); }

    public record Cancellation(@NotBlank @Size(max = 200) String reason) { }
    public record MedicalRecord(@NotBlank String symptoms, @NotBlank String diagnosis, @NotBlank String treatmentNote) { }
}
