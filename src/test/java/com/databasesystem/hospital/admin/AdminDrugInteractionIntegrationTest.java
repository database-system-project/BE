package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminDrugInteractionIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/drug-interactions";

    private ResultActions call(Actor actor, MockHttpServletRequestBuilder request) throws Exception {
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private ResultActions create(Actor actor, String json) throws Exception {
        return call(actor, post(PATH).contentType("application/json").content(json));
    }

    private ResultActions create(Actor actor, Object a, Object b, String message) throws Exception {
        return create(actor, "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + ",\"message\":\"" + message + "\"}");
    }

    private ResultActions patchAs(Actor actor, String path, String json) throws Exception {
        return call(actor, patch(PATH + path).contentType("application/json").content(json));
    }

    private List<String> pairs(String query, Actor admin) throws Exception {
        String json = getAs(PATH + query, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> result = new ArrayList<>();
        mapper.readTree(json).get("items").forEach(node ->
                result.add(node.get("medicationAId").asLong() + "-" + node.get("medicationBId").asLong()));
        return result;
    }

    private long interactionCount() { return jdbc.queryForObject("SELECT count(*) FROM drug_interaction", Long.class); }

    @Test
    void createsAnInteractionAndNormalizesReversedIds() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        create(admin, a, b, "예시 병용금기 경고").andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INTERACTION")).andExpect(jsonPath("$.medicationAId").value(a))
                .andExpect(jsonPath("$.medicationBId").value(b)).andExpect(jsonPath("$.message").value("예시 병용금기 경고"));

        long c = medication("약C");
        create(admin, c, a, " 역순 입력 ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicationAId").value(a)).andExpect(jsonPath("$.medicationBId").value(c))
                .andExpect(jsonPath("$.message").value("역순 입력"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM drug_interaction WHERE medicine_a_id < medicine_b_id", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void inactiveMedicationsCanBePaired() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        jdbc.update("UPDATE medicine SET medicine_active = FALSE WHERE medicine_id = ?", b);
        create(admin, a, b, "비활성 약품 포함").andExpect(status().isCreated());
    }

    @Test
    void duplicatePairsAreRejectedInEitherOrderAndWhenConcurrent() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        create(admin, a, b, "첫 등록").andExpect(status().isCreated());
        for (String json : List.of(
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + ",\"message\":\"중복\"}",
                "{\"medicationAId\":" + b + ",\"medicationBId\":" + a + ",\"message\":\"역순 중복\"}")) {
            create(admin, json).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_DUR_RULE"))
                    .andExpect(jsonPath("$.message").value("해당 약품 쌍의 병용금기 기준이 이미 있습니다."));
        }
        assertThat(interactionCount()).isEqualTo(1L);

        long c = medication("약C");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> create(admin, a, c, "동시1").andReturn().getResponse().getStatus(),
                    () -> create(admin, c, a, "동시2").andReturn().getResponse().getStatus());
            for (Future<Integer> result : pool.invokeAll(calls)) { statuses.add(result.get()); }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(interactionCount()).isEqualTo(2L);
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        List<String> bodies = List.of("{}", "[]", "not-json",
                "{\"medicationBId\":" + b + ",\"message\":\"m\"}", "{\"medicationAId\":" + a + ",\"message\":\"m\"}",
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + "}",
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + a + ",\"message\":\"같은 약\"}",
                "{\"medicationAId\":null,\"medicationBId\":" + b + ",\"message\":\"m\"}",
                "{\"medicationAId\":0,\"medicationBId\":" + b + ",\"message\":\"m\"}",
                "{\"medicationAId\":-1,\"medicationBId\":" + b + ",\"message\":\"m\"}",
                "{\"medicationAId\":\"" + a + "\",\"medicationBId\":" + b + ",\"message\":\"m\"}",
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + ",\"message\":\"   \"}",
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + ",\"message\":null}",
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + ",\"message\":1}",
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + ",\"message\":\"m\",\"active\":true}",
                "{\"medicationAId\":" + a + ",\"medicationBId\":" + b + ",\"message\":\"m\",\"type\":\"INTERACTION\"}");
        for (String json : bodies) {
            create(admin, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        create(admin, a, 999_999_999L, "없는 약품").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        create(admin, 999_999_998L, 999_999_999L, "둘 다 없음").andExpect(status().isNotFound());
        assertThat(interactionCount()).isZero();
    }

    @Test
    void listsInKeyOrderAndFiltersByEitherSideOfThePair() throws Exception {
        Actor admin = staff(Role.ADMIN);
        assertThat(pairs("", admin)).isEmpty();
        long a = medication("약A");
        long b = medication("약B");
        long c = medication("약C");
        long d = medication("약D");
        create(admin, c, d, "CD").andExpect(status().isCreated());
        create(admin, b, a, "AB").andExpect(status().isCreated());
        create(admin, c, a, "AC").andExpect(status().isCreated());

        assertThat(pairs("", admin)).containsExactly(a + "-" + b, a + "-" + c, c + "-" + d);
        assertThat(pairs("?medicationId=" + a, admin)).containsExactly(a + "-" + b, a + "-" + c);
        assertThat(pairs("?medicationId=" + c, admin)).containsExactly(a + "-" + c, c + "-" + d);
        assertThat(pairs("?medicationId=" + d, admin)).containsExactly(c + "-" + d);
        assertThat(pairs("?medicationId=999999999", admin)).isEmpty();
        getAs(PATH + "?medicationId=" + a, admin).andExpect(jsonPath("$.items[0].type").value("INTERACTION"))
                .andExpect(jsonPath("$.items[0].message").value("AB"));
    }

    @Test
    void invalidListQueriesAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        for (String query : List.of("?medicationId=0", "?medicationId=-1", "?medicationId=abc", "?medicationId=",
                "?unknown=1", "?medicationId=1&medicationId=2")) {
            getAs(PATH + query, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void readsOneInteractionByEitherOrder() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        create(admin, a, b, "조회용").andExpect(status().isCreated());
        for (String path : List.of("/" + a + "/" + b, "/" + b + "/" + a)) {
            getAs(PATH + path, admin).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("INTERACTION"))
                    .andExpect(jsonPath("$.medicationAId").value(a)).andExpect(jsonPath("$.medicationBId").value(b))
                    .andExpect(jsonPath("$.message").value("조회용"));
        }
        long c = medication("약C");
        getAs(PATH + "/" + a + "/" + c, admin).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getAs(PATH + "/" + a + "/999999999", admin).andExpect(status().isNotFound());
        getAs(PATH + "/" + a + "/" + a, admin).andExpect(status().isBadRequest());
        getAs(PATH + "/0/" + b, admin).andExpect(status().isBadRequest());
        getAs(PATH + "/abc/" + b, admin).andExpect(status().isBadRequest());
    }

    @Test
    void patchChangesOnlyTheMessage() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        create(admin, a, b, "원래 경고").andExpect(status().isCreated());
        patchAs(admin, "/" + a + "/" + b, "{\"message\":\"변경된 예시 병용금기 경고\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("INTERACTION")).andExpect(jsonPath("$.medicationAId").value(a))
                .andExpect(jsonPath("$.medicationBId").value(b)).andExpect(jsonPath("$.message").value("변경된 예시 병용금기 경고"));
        patchAs(admin, "/" + b + "/" + a, "{\"message\":\" 역순 경로 \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("역순 경로"));
        assertThat(jdbc.queryForObject("SELECT interaction_content FROM drug_interaction WHERE medicine_a_id = ?", String.class, a))
                .isEqualTo("역순 경로");
    }

    @Test
    void invalidPatchRequestsAreRejectedAndChangeNothing() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        long c = medication("약C");
        create(admin, a, b, "원래 경고").andExpect(status().isCreated());
        String path = "/" + a + "/" + b;
        for (String json : List.of("{}", "[]", "null", "not-json", "{\"message\":null}", "{\"message\":\"  \"}", "{\"message\":1}",
                "{\"medicationAId\":" + c + "}", "{\"medicationBId\":" + c + ",\"message\":\"m\"}",
                "{\"type\":\"INTERACTION\",\"message\":\"m\"}", "{\"active\":true,\"message\":\"m\"}", "{\"message\":\"m\",\"x\":1}")) {
            patchAs(admin, path, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        patchAs(admin, "/" + a + "/" + a, "{\"message\":\"m\"}").andExpect(status().isBadRequest());
        patchAs(admin, "/0/" + b, "{\"message\":\"m\"}").andExpect(status().isBadRequest());
        patchAs(admin, "/" + a + "/" + c, "{\"message\":\"m\"}").andExpect(status().isNotFound());
        patchAs(admin, "/" + a + "/999999999", "{\"message\":\"m\"}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT interaction_content FROM drug_interaction", String.class)).isEqualTo("원래 경고");
        assertThat(interactionCount()).isEqualTo(1L);
    }

    @Test
    void onlyAdminsWithAValidTokenCanUseTheInteractionApis() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        create(admin, a, b, "권한 확인").andExpect(status().isCreated());
        for (Actor actor : new Actor[] {null, doctor().actor(), staff(Role.CASHIER), patient()}) {
            int expected = actor == null ? 401 : 403;
            create(actor, a, b, "x").andExpect(status().is(expected));
            call(actor, get(PATH)).andExpect(status().is(expected));
            call(actor, get(PATH + "/" + a + "/" + b)).andExpect(status().is(expected));
            patchAs(actor, "/" + a + "/" + b, "{\"message\":\"x\"}").andExpect(status().is(expected));
        }
        assertThat(jdbc.queryForObject("SELECT interaction_content FROM drug_interaction", String.class)).isEqualTo("권한 확인");
    }
}
