package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.Values;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminConsultationFeeIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/consultation-fee";
    private static final Instant PAST = NOW.minusSeconds(30L * 86_400);

    private ResultActions patchJson(Actor actor, String json) throws Exception {
        var request = patch(PATH).contentType("application/json").content(json);
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private ResultActions patchFee(Actor actor, Object type, Object amount, Object from) throws Exception {
        var body = Values.map("feeType", type, "amount", amount, "effectiveFrom", from);
        var request = patch(PATH).contentType("application/json").content(mapper.writeValueAsString(body));
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private long feeCount() { return jdbc.queryForObject("SELECT count(*) FROM consultation_fee", Long.class); }

    @Test
    void addsANewFeeRowAndRecordsTheAdmin() throws Exception {
        Actor admin = staff(Role.ADMIN);
        patchFee(admin, "FIRST_VISIT", 19000, "2026-12-01T00:00:00+09:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.feeId").isNumber()).andExpect(jsonPath("$.feeType").value("FIRST_VISIT"))
                .andExpect(jsonPath("$.amount").value(19000)).andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-12-01T00:00:00+09:00"));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM consultation_fee");
        assertThat(row).containsEntry("fee_type", "FIRST_VISIT").containsEntry("fee_amount", 19000)
                .containsEntry("set_by_staff_id", admin.staffId());
        assertThat(jdbc.queryForObject("SELECT effective_from = TIMESTAMPTZ '2026-11-30 15:00:00+00' FROM consultation_fee",
                Boolean.class)).isTrue();
    }

    @Test
    void neverOverwritesExistingRowsAndFutureRowsWaitForTheirStart() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long oldFirst = fee("FIRST_VISIT", 18000, PAST, admin);
        long oldFollow = fee("FOLLOW_UP", 15000, PAST, admin);

        patchFee(admin, "FIRST_VISIT", 19000, "2026-12-01T00:00:00+09:00").andExpect(status().isOk());
        assertThat(feeCount()).isEqualTo(3L);
        getAs(PATH, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].feeId").value(oldFirst)).andExpect(jsonPath("$.items[0].amount").value(18000))
                .andExpect(jsonPath("$.items[1].feeId").value(oldFollow));

        // 처리 시각(2026-11-10T09:00+09:00)과 같은 시작일시부터 적용된다.
        long applied = mapper.readTree(patchFee(admin, "FIRST_VISIT", 20000, "2026-11-10T09:00:00+09:00")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("feeId").asLong();
        getAs(PATH, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].feeId").value(applied)).andExpect(jsonPath("$.items[0].amount").value(20000));
        assertThat(jdbc.queryForObject("SELECT fee_amount FROM consultation_fee WHERE fee_id = ?", Integer.class, oldFirst))
                .isEqualTo(18000);
        assertThat(feeCount()).isEqualTo(4L);
    }

    @Test
    void currentFeesListFirstVisitThenFollowUp() throws Exception {
        Actor admin = staff(Role.ADMIN);
        fee("FOLLOW_UP", 15000, PAST, admin);
        fee("FIRST_VISIT", 18000, PAST, admin);
        fee("FIRST_VISIT", 17000, PAST.minusSeconds(86_400), admin);
        getAs(PATH, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].feeType").value("FIRST_VISIT")).andExpect(jsonPath("$.items[0].amount").value(18000))
                .andExpect(jsonPath("$.items[0].currency").value("KRW"))
                .andExpect(jsonPath("$.items[0].effectiveFrom").isString())
                .andExpect(jsonPath("$.items[1].feeType").value("FOLLOW_UP")).andExpect(jsonPath("$.items[1].amount").value(15000));
    }

    @Test
    void missingFeeTypesAreReportedWithDetails() throws Exception {
        Actor admin = staff(Role.ADMIN);
        getAs(PATH, admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FEE_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value("해당 진료 유형에 적용할 진찰료가 설정되어 있지 않습니다."))
                .andExpect(jsonPath("$.details.feeTypes[0]").value("FIRST_VISIT"))
                .andExpect(jsonPath("$.details.feeTypes[1]").value("FOLLOW_UP"));

        fee("FIRST_VISIT", 18000, PAST, admin);
        fee("FOLLOW_UP", 15000, NOW.plusSeconds(86_400), admin); // 미래 행만 있으면 아직 설정되지 않은 것이다.
        getAs(PATH, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.feeTypes.length()").value(1))
                .andExpect(jsonPath("$.details.feeTypes[0]").value("FOLLOW_UP"));
    }

    @Test
    void duplicateTypeAndStartIsRejectedEvenWithAnotherOffset() throws Exception {
        Actor admin = staff(Role.ADMIN);
        patchFee(admin, "FIRST_VISIT", 19000, "2026-12-01T00:00:00+09:00").andExpect(status().isOk());
        for (String same : List.of("2026-12-01T00:00:00+09:00", "2026-11-30T15:00:00Z", "2026-11-30T15:00:00+00:00")) {
            patchFee(admin, "FIRST_VISIT", 25000, same).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_FEE_EFFECTIVE_FROM"))
                    .andExpect(jsonPath("$.message").value("해당 진찰료 유형·적용 시작일의 요금이 이미 있습니다."));
        }
        patchFee(admin, "FOLLOW_UP", 16000, "2026-12-01T00:00:00+09:00").andExpect(status().isOk());
        patchFee(admin, "FIRST_VISIT", 19500, "2026-12-01T00:00:01+09:00").andExpect(status().isOk());
        assertThat(feeCount()).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT fee_amount FROM consultation_fee WHERE fee_type = 'FIRST_VISIT' "
                + "AND effective_from = TIMESTAMPTZ '2026-11-30 15:00:00+00'", Integer.class)).isEqualTo(19000);
    }

    @Test
    void sameRequestAtTheSameTimeInsertsOnlyOnce() throws Exception {
        Actor admin = staff(Role.ADMIN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> patchFee(admin, "FOLLOW_UP", 15000, "2026-12-01T00:00:00+09:00").andReturn().getResponse().getStatus(),
                    () -> patchFee(admin, "FOLLOW_UP", 16000, "2026-12-01T00:00:00+09:00").andReturn().getResponse().getStatus());
            for (Future<Integer> result : pool.invokeAll(calls)) { statuses.add(result.get()); }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(feeCount()).isEqualTo(1L);
    }

    @Test
    void invalidRequestsAreRejectedWithoutInsertingAnything() throws Exception {
        Actor admin = staff(Role.ADMIN);
        List<String> bodies = List.of("{}",
                "{\"amount\":19000,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":19000}",
                "{\"feeType\":null,\"amount\":19000,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":null,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"OTHER\",\"amount\":19000,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"first_visit\",\"amount\":19000,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":1,\"amount\":19000,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":0,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":-1,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":2147483648,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":1.5,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":\"19000\",\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":19000,\"effectiveFrom\":\"2026-12-01T00:00:00\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":19000,\"effectiveFrom\":\"2026-12-01\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":19000,\"effectiveFrom\":\"garbage\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":19000,\"effectiveFrom\":\"+12345-01-01T00:00:00Z\"}",
                "{\"feeType\":\"FIRST_VISIT\",\"amount\":19000,\"effectiveFrom\":\"2026-12-01T00:00:00+09:00\",\"version\":2}",
                "[]", "not-json");
        for (String json : bodies) {
            patchJson(admin, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(patch(PATH).header("Authorization", "Bearer " + admin.token())).andExpect(status().isBadRequest());
        getAs(PATH + "?feeType=FIRST_VISIT", admin).andExpect(status().isBadRequest());
        assertThat(feeCount()).isZero();
    }

    @Test
    void onlyAdminsCanReadOrChangeFees() throws Exception {
        Actor admin = staff(Role.ADMIN);
        fee("FIRST_VISIT", 18000, PAST, admin);
        fee("FOLLOW_UP", 15000, PAST, admin);
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), staff(Role.DOCTOR), patient())) {
            getAs(PATH, other).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            patchFee(other, "FIRST_VISIT", 19000, "2026-12-01T00:00:00+09:00").andExpect(status().isForbidden());
        }
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        patchFee(null, "FIRST_VISIT", 19000, "2026-12-01T00:00:00+09:00").andExpect(status().isUnauthorized());
        assertThat(feeCount()).isEqualTo(2L);
    }
}
