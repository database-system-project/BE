package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/doctor/schedules")
public class DoctorScheduleController {
    private final DoctorScheduleService service;

    public DoctorScheduleController(DoctorScheduleService service) { this.service = service; }

    @GetMapping
    @QueryParameters({"from", "to"})
    public Map<String, Object> list(@AuthenticationPrincipal HospitalPrincipal user,
                                    @RequestParam Map<String, String> query) {
        return service.list(user, query);
    }
}
