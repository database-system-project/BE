package com.databasesystem.hospital.reservation;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.common.QueryParameters;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 환자가 예약 화면에서 쓰는 조회 API: 진료과 목록, 진료과별 의사 목록, 예약 가능 시간. */
@RestController
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) { this.service = service; }

    @GetMapping("/api/v1/departments")
    public Map<String, Object> departments(@AuthenticationPrincipal HospitalPrincipal user) {
        return service.departments(user);
    }

    @GetMapping("/api/v1/departments/{departmentId}/doctors")
    public Map<String, Object> doctors(@AuthenticationPrincipal HospitalPrincipal user,
                                       @PathVariable long departmentId) {
        return service.doctors(user, departmentId);
    }

    @GetMapping("/api/v1/doctors/{doctorId}/availability")
    @QueryParameters({"from", "to"})
    public Map<String, Object> availability(@AuthenticationPrincipal HospitalPrincipal user,
                                            @PathVariable long doctorId,
                                            @RequestParam Map<String, String> query) {
        return service.availability(user, doctorId, query);
    }
}
