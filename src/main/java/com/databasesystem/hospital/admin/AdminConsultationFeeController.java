package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/consultation-fee")
public class AdminConsultationFeeController {
    private final AdminConsultationFeeService service;

    public AdminConsultationFeeController(AdminConsultationFeeService service) { this.service = service; }

    /** 초진·재진별 현행 요금을 조회한다. 쿼리 파라미터는 받지 않는다. */
    @GetMapping
    public Map<String, Object> current(@AuthenticationPrincipal HospitalPrincipal user) {
        return service.current(user);
    }

    /** 요금을 덮어쓰지 않고 새 이력 행을 추가한다. 성공 시 200과 새로 만든 행을 돌려준다. */
    @PatchMapping
    public Map<String, Object> add(@AuthenticationPrincipal HospitalPrincipal user,
                                   @Valid @RequestBody FeeRequest request) {
        return service.add(user, request);
    }

    public enum FeeType { FIRST_VISIT, FOLLOW_UP }

    public record FeeRequest(@NotNull FeeType feeType, @NotNull @Positive Integer amount,
                             @NotNull OffsetDateTime effectiveFrom) { }
}
