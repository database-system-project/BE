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

class AdminMedicationIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/medications";

    private ResultActions call(Actor actor, MockHttpServletRequestBuilder request) throws Exception {
        if (actor != null) { request.header("Authorization", "Bearer " + actor.token()); }
        return mvc.perform(request);
    }

    private ResultActions create(Actor actor, String json) throws Exception {
        return call(actor, post(PATH).contentType("application/json").content(json));
    }

    private ResultActions patchAs(Actor actor, Object id, String json) throws Exception {
        return call(actor, patch(PATH + "/" + id).contentType("application/json").content(json));
    }

    private static String body(String itemSeq) {
        return "{\"itemSeq\":\"" + itemSeq + "\",\"name\":\"시연용 약품\",\"companyName\":\"시연제약\",\"active\":true}";
    }

    private long createdId(Actor admin, String itemSeq) throws Exception {
        String json = create(admin, body(itemSeq)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("medicationId").asLong();
    }

    private List<Long> ids(String query, Actor admin) throws Exception {
        String json = getAs(PATH + query, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("items").findValues("medicationId").stream().map(node -> node.asLong()).toList();
    }

    private long medicineCount() { return jdbc.queryForObject("SELECT count(*) FROM medicine", Long.class); }

    private void setActive(long id, boolean active) {
        jdbc.update("UPDATE medicine SET medicine_active = ? WHERE medicine_id = ?", active, id);
    }

    @Test
    void createsAMedicationWithAllFields() throws Exception {
        Actor admin = staff(Role.ADMIN);
        create(admin, """
                {"itemSeq":"EXAMPLE000041","name":"시연용 약품 A","companyName":"시연제약","efficacy":"시연용 효능 안내",
                 "usage":"시연용 사용법","precautions":"시연용 주의사항","storage":"시연용 보관법","active":true}
                """).andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicationId").isNumber()).andExpect(jsonPath("$.itemSeq").value("EXAMPLE000041"))
                .andExpect(jsonPath("$.name").value("시연용 약품 A")).andExpect(jsonPath("$.companyName").value("시연제약"))
                .andExpect(jsonPath("$.efficacy").value("시연용 효능 안내")).andExpect(jsonPath("$.usage").value("시연용 사용법"))
                .andExpect(jsonPath("$.precautions").value("시연용 주의사항")).andExpect(jsonPath("$.storage").value("시연용 보관법"))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(jdbc.queryForObject("SELECT usage_text FROM medicine WHERE item_seq = 'EXAMPLE000041'", String.class))
                .isEqualTo("시연용 사용법");
    }

    @Test
    void missingNullAndBlankNotesAreStoredAsNull() throws Exception {
        Actor admin = staff(Role.ADMIN);
        create(admin, body("NOTES1")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.efficacy").isEmpty()).andExpect(jsonPath("$.usage").isEmpty())
                .andExpect(jsonPath("$.precautions").isEmpty()).andExpect(jsonPath("$.storage").isEmpty());
        create(admin, """
                {"itemSeq":"NOTES2","name":"약","companyName":"회사","efficacy":null,"usage":"   ","precautions":"","storage":" 냉장 ","active":false}
                """).andExpect(status().isCreated())
                .andExpect(jsonPath("$.efficacy").isEmpty()).andExpect(jsonPath("$.usage").isEmpty())
                .andExpect(jsonPath("$.precautions").isEmpty()).andExpect(jsonPath("$.storage").value("냉장"))
                .andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.queryForObject("SELECT usage_text FROM medicine WHERE item_seq = 'NOTES2'", String.class)).isNull();
    }

    @Test
    void duplicateItemSeqIsRejectedIncludingInactiveAndConcurrentRequests() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long id = createdId(admin, "DUP1");
        setActive(id, false);
        create(admin, body("DUP1")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_ITEM_SEQ"))
                .andExpect(jsonPath("$.message").value("해당 품목기준코드의 약품이 이미 있습니다."));
        assertThat(medicineCount()).isEqualTo(1L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            Callable<Integer> call = () -> create(admin, body("RACE1")).andReturn().getResponse().getStatus();
            for (Future<Integer> result : pool.invokeAll(List.of(call, call))) { statuses.add(result.get()); }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(medicineCount()).isEqualTo(2L);
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        String ok = "\"itemSeq\":\"X1\",\"name\":\"약\",\"companyName\":\"회사\",\"active\":true";
        List<String> bodies = List.of("{}", "[]", "not-json",
                "{\"name\":\"약\",\"companyName\":\"회사\",\"active\":true}",
                "{\"itemSeq\":\"X1\",\"companyName\":\"회사\",\"active\":true}",
                "{\"itemSeq\":\"X1\",\"name\":\"약\",\"active\":true}",
                "{\"itemSeq\":\"X1\",\"name\":\"약\",\"companyName\":\"회사\"}",
                "{\"itemSeq\":null,\"name\":\"약\",\"companyName\":\"회사\",\"active\":true}",
                "{\"itemSeq\":\"  \",\"name\":\"약\",\"companyName\":\"회사\",\"active\":true}",
                "{\"itemSeq\":\"" + "1".repeat(21) + "\",\"name\":\"약\",\"companyName\":\"회사\",\"active\":true}",
                "{\"itemSeq\":\"X1\",\"name\":\"" + "가".repeat(201) + "\",\"companyName\":\"회사\",\"active\":true}",
                "{\"itemSeq\":\"X1\",\"name\":\"약\",\"companyName\":\"" + "가".repeat(101) + "\",\"active\":true}",
                "{\"itemSeq\":123,\"name\":\"약\",\"companyName\":\"회사\",\"active\":true}",
                "{\"itemSeq\":\"X1\",\"name\":\"약\",\"companyName\":\"회사\",\"active\":\"true\"}",
                "{\"itemSeq\":\"X1\",\"name\":\"약\",\"companyName\":\"회사\",\"active\":null}",
                "{\"itemSeq\":\"X1\",\"name\":\"약\",\"companyName\":\"회사\",\"efficacy\":1,\"active\":true}",
                "{" + ok + ",\"medicationId\":5}", "{" + ok + ",\"unknown\":1}");
        for (String json : bodies) {
            create(admin, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(medicineCount()).isZero();
    }

    @Test
    void listsInNameOrderWithKeywordAndActiveFilters() throws Exception {
        Actor admin = staff(Role.ADMIN);
        assertThat(ids("", admin)).isEmpty();
        getAs(PATH, admin).andExpect(jsonPath("$.items").isArray());

        // 정렬 순서가 DB 콜레이션(한글/영문 우선순위)에 흔들리지 않도록 이름은 영문으로 시작하게 한다.
        long b = jdbc.queryForObject("""
                INSERT INTO medicine(item_seq, product_name, company_name) VALUES ('CODE-B','Zeta','회사') RETURNING medicine_id""", Long.class);
        long a = jdbc.queryForObject("""
                INSERT INTO medicine(item_seq, product_name, company_name, medicine_active) VALUES ('CODE-A','Bravo가나다정','회사',false)
                RETURNING medicine_id""", Long.class);
        long c = jdbc.queryForObject("""
                INSERT INTO medicine(item_seq, product_name, company_name) VALUES ('ZZZ9','Aspirin','회사') RETURNING medicine_id""", Long.class);
        long d = jdbc.queryForObject("""
                INSERT INTO medicine(item_seq, product_name, company_name) VALUES ('ZZZ8','Aspirin','회사') RETURNING medicine_id""", Long.class);

        // 이름 오름차순, 같은 이름은 medicationId 오름차순. 비활성도 포함한다.
        assertThat(ids("", admin)).containsExactly(c, d, a, b);
        assertThat(ids("?active=true", admin)).containsExactly(c, d, b);
        assertThat(ids("?active=false", admin)).containsExactly(a);
        assertThat(ids("?keyword=aspir", admin)).containsExactly(c, d);
        assertThat(ids("?keyword=zzz9", admin)).containsExactly(c);
        assertThat(ids("?keyword=code-", admin)).containsExactly(a, b);
        assertThat(ids("?keyword=가나&active=false", admin)).containsExactly(a);
        assertThat(ids("?keyword=  ", admin)).containsExactly(c, d, a, b);
        assertThat(ids("?keyword=nothing", admin)).isEmpty();
    }

    @Test
    void invalidListQueriesAreRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        for (String query : List.of("?active=maybe", "?unknown=1", "?active=true&active=false",
                "?keyword=a&keyword=b", "?keyword=" + "a".repeat(201))) {
            getAs(PATH + query, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void readsOneMedicationIncludingInactiveOnes() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long id = createdId(admin, "ONE1");
        setActive(id, false);
        getAs(PATH + "/" + id, admin).andExpect(status().isOk()).andExpect(jsonPath("$.medicationId").value(id))
                .andExpect(jsonPath("$.itemSeq").value("ONE1")).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.efficacy").isEmpty());
        getAs(PATH + "/999999999", admin).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        getAs(PATH + "/0", admin).andExpect(status().isBadRequest());
        getAs(PATH + "/-1", admin).andExpect(status().isBadRequest());
        getAs(PATH + "/abc", admin).andExpect(status().isBadRequest());
    }

    @Test
    void patchChangesOnlyTheSentFields() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long id = jdbc.queryForObject("""
                INSERT INTO medicine(item_seq, product_name, company_name, efficacy, usage_text, precautions, storage)
                VALUES ('PATCH1','원래이름','원래회사','효능','사용법','주의','보관') RETURNING medicine_id""", Long.class);
        patchAs(admin, id, "{\"active\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("원래이름")).andExpect(jsonPath("$.companyName").value("원래회사"))
                .andExpect(jsonPath("$.efficacy").value("효능")).andExpect(jsonPath("$.itemSeq").value("PATCH1"));
        patchAs(admin, id, "{\"name\":\"  새이름 \",\"companyName\":\"새회사\",\"usage\":\"새 사용법\",\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("새이름"))
                .andExpect(jsonPath("$.companyName").value("새회사")).andExpect(jsonPath("$.usage").value("새 사용법"))
                .andExpect(jsonPath("$.precautions").value("주의")).andExpect(jsonPath("$.active").value(true));
        // null·공백 문자열은 해당 안내 문구를 결측으로 만든다. 나머지는 유지된다.
        patchAs(admin, id, "{\"efficacy\":null,\"precautions\":\"   \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.efficacy").isEmpty()).andExpect(jsonPath("$.precautions").isEmpty())
                .andExpect(jsonPath("$.storage").value("보관")).andExpect(jsonPath("$.name").value("새이름"));
        assertThat(jdbc.queryForObject("SELECT efficacy FROM medicine WHERE medicine_id = ?", String.class, id)).isNull();
        // 값이 같은 요청도 정상 처리한다.
        patchAs(admin, id, "{\"name\":\"새이름\"}").andExpect(status().isOk());
    }

    @Test
    void invalidPatchRequestsAreRejectedAndChangeNothing() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long id = createdId(admin, "PATCH2");
        long other = createdId(admin, "PATCH3");
        List<String> bodies = List.of("{}", "[]", "null", "not-json",
                "{\"itemSeq\":\"NEW\"}", "{\"medicationId\":9}", "{\"unknown\":1}",
                "{\"name\":null}", "{\"name\":\"  \"}", "{\"name\":\"" + "가".repeat(201) + "\"}", "{\"name\":1}",
                "{\"companyName\":null}", "{\"companyName\":\"\"}", "{\"companyName\":\"" + "가".repeat(101) + "\"}",
                "{\"active\":null}", "{\"active\":\"true\"}", "{\"efficacy\":1}", "{\"storage\":true}");
        for (String json : bodies) {
            patchAs(admin, id, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        patchAs(admin, 999_999_999L, "{\"active\":false}").andExpect(status().isNotFound());
        patchAs(admin, 0, "{\"active\":false}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT product_name || medicine_active FROM medicine WHERE medicine_id = ?", String.class, id))
                .isEqualTo("시연용 약품true");
        assertThat(jdbc.queryForObject("SELECT item_seq FROM medicine WHERE medicine_id = ?", String.class, other)).isEqualTo("PATCH3");
    }

    @Test
    void onlyAdminsWithAValidTokenCanUseTheMedicationApis() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long id = createdId(admin, "AUTH1");
        for (Actor actor : new Actor[] {null, doctor().actor(), staff(Role.RECEPTION), patient()}) {
            int expected = actor == null ? 401 : 403;
            create(actor, body("AUTH2")).andExpect(status().is(expected));
            call(actor, get(PATH)).andExpect(status().is(expected));
            call(actor, get(PATH + "/" + id)).andExpect(status().is(expected));
            patchAs(actor, id, "{\"active\":false}").andExpect(status().is(expected));
        }
        assertThat(medicineCount()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT medicine_active FROM medicine WHERE medicine_id = ?", Boolean.class, id)).isTrue();
    }
}
