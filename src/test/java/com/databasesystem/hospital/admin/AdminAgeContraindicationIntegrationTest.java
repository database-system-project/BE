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

class AdminAgeContraindicationIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/age-contraindications";

    private ResultActions call(Actor actor, MockHttpServletRequestBuilder request) throws Exception {
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private ResultActions create(Actor actor, String json) throws Exception {
        return call(actor, post(PATH).contentType("application/json").content(json));
    }

    private ResultActions create(Actor actor, Object medication, Object limit, String operator, String message) throws Exception {
        return create(actor, "{\"medicationId\":" + medication + ",\"ageLimit\":" + limit
                + ",\"ageOperator\":\"" + operator + "\",\"message\":\"" + message + "\"}");
    }

    private ResultActions patchAs(Actor actor, String path, String json) throws Exception {
        return call(actor, patch(PATH + path).contentType("application/json").content(json));
    }

    private List<String> keys(String query, Actor admin) throws Exception {
        String json = getAs(PATH + query, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> result = new ArrayList<>();
        mapper.readTree(json).get("items").forEach(node ->
                result.add(node.get("medicationId").asLong() + "/" + node.get("ageSeq").asLong()));
        return result;
    }

    private long ruleCount() { return jdbc.queryForObject("SELECT count(*) FROM age_contraindication", Long.class); }

    @Test
    void createsRulesAndIssuesTheNextSequencePerMedication() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        create(admin, a, 12, "LT", "예시 연령 기준 경고").andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("AGE")).andExpect(jsonPath("$.medicationId").value(a))
                .andExpect(jsonPath("$.ageSeq").value(1)).andExpect(jsonPath("$.ageLimit").value(12))
                .andExpect(jsonPath("$.ageOperator").value("LT")).andExpect(jsonPath("$.message").value("예시 연령 기준 경고"));
        create(admin, a, 12, "LTE", " 이하 ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.ageSeq").value(2)).andExpect(jsonPath("$.message").value("이하"));
        create(admin, a, 0, "LT", "경계 0").andExpect(status().isCreated()).andExpect(jsonPath("$.ageSeq").value(3));
        create(admin, a, 32767, "LTE", "경계 최대").andExpect(status().isCreated()).andExpect(jsonPath("$.ageSeq").value(4));
        // 순번은 약품별로 따로 발급한다.
        create(admin, b, 12, "LT", "다른 약품").andExpect(status().isCreated()).andExpect(jsonPath("$.ageSeq").value(1));
        assertThat(ruleCount()).isEqualTo(5L);
    }

    @Test
    void duplicateConditionsAreRejectedAndConcurrentRequestsGetDistinctSequences() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        create(admin, a, 12, "LT", "첫 등록").andExpect(status().isCreated());
        create(admin, a, 12, "LT", "중복").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DUR_RULE"))
                .andExpect(jsonPath("$.message").value("동일한 약품·연령 조건의 기준이 이미 있습니다."));
        assertThat(ruleCount()).isEqualTo(1L);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                int limit = 20 + index;
                calls.add(() -> create(admin, a, limit, "LT", "동시 " + limit).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> result : pool.invokeAll(calls)) { statuses.add(result.get()); }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsOnly(201);
        assertThat(jdbc.queryForList("SELECT age_seq FROM age_contraindication WHERE medicine_id = ? ORDER BY age_seq", Integer.class, a))
                .containsExactly(1, 2, 3, 4, 5);

        // 같은 조건을 동시에 보내면 하나만 저장된다.
        pool = Executors.newFixedThreadPool(2);
        statuses.clear();
        try {
            Callable<Integer> same = () -> create(admin, a, 50, "LTE", "같은 조건").andReturn().getResponse().getStatus();
            for (Future<Integer> result : pool.invokeAll(List.of(same, same))) { statuses.add(result.get()); }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(ruleCount()).isEqualTo(6L);
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        String m = "\"medicationId\":" + a;
        List<String> bodies = List.of("{}", "[]", "not-json",
                "{\"ageLimit\":12,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12,\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12,\"ageOperator\":\"LT\"}",
                "{\"medicationId\":null,\"ageLimit\":12,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{\"medicationId\":0,\"ageLimit\":12,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":-1,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":32768,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12.5,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":\"12\",\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":null,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12,\"ageOperator\":\"GT\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12,\"ageOperator\":\"lt\",\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12,\"ageOperator\":0,\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12,\"ageOperator\":null,\"message\":\"m\"}",
                "{" + m + ",\"ageLimit\":12,\"ageOperator\":\"LT\",\"message\":\"   \"}",
                "{" + m + ",\"ageLimit\":12,\"ageOperator\":\"LT\",\"message\":null}",
                "{" + m + ",\"ageSeq\":5,\"ageLimit\":12,\"ageOperator\":\"LT\",\"message\":\"m\"}",
                "{" + m + ",\"type\":\"AGE\",\"ageLimit\":12,\"ageOperator\":\"LT\",\"message\":\"m\"}");
        for (String json : bodies) {
            create(admin, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        create(admin, 999_999_999L, 12, "LT", "없는 약품").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(ruleCount()).isZero();
    }

    @Test
    void listsInKeyOrderAndFiltersByMedication() throws Exception {
        Actor admin = staff(Role.ADMIN);
        assertThat(keys("", admin)).isEmpty();
        long a = medication("약A");
        long b = medication("약B");
        create(admin, b, 5, "LT", "B1").andExpect(status().isCreated());
        create(admin, a, 12, "LT", "A1").andExpect(status().isCreated());
        create(admin, a, 65, "LTE", "A2").andExpect(status().isCreated());
        create(admin, b, 1, "LTE", "B2").andExpect(status().isCreated());

        assertThat(keys("", admin)).containsExactly(a + "/1", a + "/2", b + "/1", b + "/2");
        assertThat(keys("?medicationId=" + a, admin)).containsExactly(a + "/1", a + "/2");
        assertThat(keys("?medicationId=" + b, admin)).containsExactly(b + "/1", b + "/2");
        assertThat(keys("?medicationId=999999999", admin)).isEmpty();
        getAs(PATH + "?medicationId=" + a, admin).andExpect(jsonPath("$.items[1].type").value("AGE"))
                .andExpect(jsonPath("$.items[1].ageLimit").value(65)).andExpect(jsonPath("$.items[1].ageOperator").value("LTE"))
                .andExpect(jsonPath("$.items[1].message").value("A2"));
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
    void readsOneRuleByItsCompositeKey() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        create(admin, a, 12, "LT", "조회용").andExpect(status().isCreated());
        getAs(PATH + "/" + a + "/1", admin).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("AGE"))
                .andExpect(jsonPath("$.medicationId").value(a)).andExpect(jsonPath("$.ageSeq").value(1))
                .andExpect(jsonPath("$.ageLimit").value(12)).andExpect(jsonPath("$.ageOperator").value("LT"))
                .andExpect(jsonPath("$.message").value("조회용"));
        getAs(PATH + "/" + a + "/2", admin).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getAs(PATH + "/999999999/1", admin).andExpect(status().isNotFound());
        getAs(PATH + "/" + a + "/0", admin).andExpect(status().isBadRequest());
        getAs(PATH + "/0/1", admin).andExpect(status().isBadRequest());
        getAs(PATH + "/" + a + "/abc", admin).andExpect(status().isBadRequest());
    }

    @Test
    void patchChangesOnlyTheSentFieldsAndKeepsTheKey() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        create(admin, a, 12, "LT", "원래 경고").andExpect(status().isCreated());
        String path = "/" + a + "/1";
        patchAs(admin, path, "{\"message\":\"새 경고\"}").andExpect(status().isOk()).andExpect(jsonPath("$.ageLimit").value(12))
                .andExpect(jsonPath("$.ageOperator").value("LT")).andExpect(jsonPath("$.message").value("새 경고"));
        patchAs(admin, path, "{\"ageOperator\":\"LTE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.ageOperator").value("LTE")).andExpect(jsonPath("$.ageLimit").value(12));
        patchAs(admin, path, "{\"ageLimit\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.ageLimit").value(0));
        patchAs(admin, path, "{\"ageLimit\":12,\"ageOperator\":\"LTE\",\"message\":\"변경된 예시 연령 기준 경고\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("AGE")).andExpect(jsonPath("$.medicationId").value(a))
                .andExpect(jsonPath("$.ageSeq").value(1)).andExpect(jsonPath("$.ageLimit").value(12))
                .andExpect(jsonPath("$.ageOperator").value("LTE")).andExpect(jsonPath("$.message").value("변경된 예시 연령 기준 경고"));
        // 값이 그대로여도 자기 자신과는 중복이 아니다.
        patchAs(admin, path, "{\"ageLimit\":12,\"ageOperator\":\"LTE\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT age_condition || age_limit || age_seq FROM age_contraindication", String.class))
                .isEqualTo("LTE121");
    }

    @Test
    void patchRejectsAFinalConditionThatAlreadyExists() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        long b = medication("약B");
        create(admin, a, 12, "LT", "1번").andExpect(status().isCreated());
        create(admin, a, 12, "LTE", "2번").andExpect(status().isCreated());
        create(admin, a, 65, "LT", "3번").andExpect(status().isCreated());
        create(admin, b, 65, "LT", "다른 약품").andExpect(status().isCreated());

        patchAs(admin, "/" + a + "/1", "{\"ageOperator\":\"LTE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DUR_RULE"))
                .andExpect(jsonPath("$.message").value("동일한 약품·연령 조건의 기준이 이미 있습니다."));
        patchAs(admin, "/" + a + "/3", "{\"ageLimit\":12,\"ageOperator\":\"LT\"}").andExpect(status().isConflict());
        // 같은 나이·연산자를 가진 다른 약품(b)의 기준은 충돌 대상이 아니다. 충돌하지 않는 값으로는 수정된다.
        patchAs(admin, "/" + a + "/1", "{\"ageLimit\":30}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT age_condition || age_limit FROM age_contraindication WHERE medicine_id = ? AND age_seq = 2",
                String.class, a)).isEqualTo("LTE12");
    }

    @Test
    void invalidPatchRequestsAreRejectedAndChangeNothing() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        create(admin, a, 12, "LT", "원래 경고").andExpect(status().isCreated());
        String path = "/" + a + "/1";
        for (String json : List.of("{}", "[]", "null", "not-json", "{\"ageLimit\":null}", "{\"ageLimit\":-1}", "{\"ageLimit\":32768}",
                "{\"ageLimit\":12.5}", "{\"ageLimit\":\"12\"}", "{\"ageOperator\":null}", "{\"ageOperator\":\"GT\"}",
                "{\"ageOperator\":\"lt\"}", "{\"ageOperator\":1}", "{\"message\":null}", "{\"message\":\"  \"}", "{\"message\":1}",
                "{\"ageSeq\":2}", "{\"medicationId\":1}", "{\"type\":\"AGE\",\"message\":\"m\"}", "{\"message\":\"m\",\"x\":1}")) {
            patchAs(admin, path, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        patchAs(admin, "/" + a + "/2", "{\"message\":\"m\"}").andExpect(status().isNotFound());
        patchAs(admin, "/999999999/1", "{\"message\":\"m\"}").andExpect(status().isNotFound());
        patchAs(admin, "/" + a + "/0", "{\"message\":\"m\"}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT age_content || age_limit || age_condition FROM age_contraindication", String.class))
                .isEqualTo("원래 경고12LT");
    }

    @Test
    void onlyAdminsWithAValidTokenCanUseTheAgeContraindicationApis() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long a = medication("약A");
        create(admin, a, 12, "LT", "권한 확인").andExpect(status().isCreated());
        for (Actor actor : new Actor[] {null, staff(Role.DOCTOR), staff(Role.RECEPTION), patient()}) {
            int expected = actor == null ? 401 : 403;
            create(actor, a, 13, "LT", "x").andExpect(status().is(expected));
            call(actor, get(PATH)).andExpect(status().is(expected));
            call(actor, get(PATH + "/" + a + "/1")).andExpect(status().is(expected));
            patchAs(actor, "/" + a + "/1", "{\"message\":\"x\"}").andExpect(status().is(expected));
        }
        assertThat(ruleCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT age_content FROM age_contraindication", String.class)).isEqualTo("권한 확인");
    }
}
