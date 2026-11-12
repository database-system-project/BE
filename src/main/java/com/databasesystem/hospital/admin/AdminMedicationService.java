package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.HospitalPrincipal;
import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.common.ApiException;
import com.databasesystem.hospital.common.Database;
import com.databasesystem.hospital.common.Values;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminMedicationService {
    private static final String SELECT = """
            SELECT medicine_id, item_seq, product_name, company_name, efficacy, usage_text, precautions, storage,
                   medicine_active
              FROM medicine
            """;
    private final Database db;

    public AdminMedicationService(Database db) { this.db = db; }

    /** 품목기준코드는 비활성 약품을 포함해 유일하다. 동시 요청은 ON CONFLICT가 하나만 통과시킨다. */
    @Transactional
    public Map<String, Object> create(HospitalPrincipal user, AdminMedicationController.CreateRequest request) {
        user.require(Role.ADMIN);
        String itemSeq = Values.clean(request.itemSeq(), 20);
        String name = Values.clean(request.name(), 200);
        String company = Values.clean(request.companyName(), 100);
        List<Map<String, Object>> inserted = db.rows("""
                INSERT INTO medicine(item_seq, product_name, company_name, efficacy, usage_text, precautions, storage,
                                     medicine_active)
                VALUES (:itemSeq, :name, :company, :efficacy, :usage, :precautions, :storage, :active)
                ON CONFLICT (item_seq) DO NOTHING
                RETURNING medicine_id
                """, Values.map("itemSeq", itemSeq, "name", name, "company", company,
                "efficacy", note(request.efficacy()), "usage", note(request.usage()),
                "precautions", note(request.precautions()), "storage", note(request.storage()),
                "active", request.active()));
        if (inserted.isEmpty()) {
            throw ApiException.conflict("DUPLICATE_ITEM_SEQ", "해당 품목기준코드의 약품이 이미 있습니다.");
        }
        return view(db.one(SELECT + " WHERE medicine_id = :id", Map.of("id", Values.id(inserted.get(0), "medicine_id"))));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(HospitalPrincipal user, String keyword, Boolean active) {
        user.require(Role.ADMIN);
        String word = keyword == null || keyword.strip().isEmpty() ? null : Values.clean(keyword, 200);
        StringBuilder sql = new StringBuilder(SELECT + " WHERE TRUE");
        Map<String, Object> parameters = new HashMap<>();
        if (word != null) {
            sql.append(" AND (position(lower(:keyword) in lower(product_name)) > 0")
               .append(" OR position(lower(:keyword) in lower(item_seq)) > 0)");
            parameters.put("keyword", word);
        }
        if (active != null) {
            sql.append(" AND medicine_active = :active");
            parameters.put("active", active);
        }
        sql.append(" ORDER BY product_name, medicine_id");
        List<Map<String, Object>> items = db.rows(sql.toString(), parameters).stream().map(this::view).toList();
        return Map.of("items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(HospitalPrincipal user, long medicationId) {
        user.require(Role.ADMIN);
        positive(medicationId);
        return view(db.one(SELECT + " WHERE medicine_id = :id", Map.of("id", medicationId)));
    }

    /**
     * 보낸 필드만 수정한다. 필수 값(name, companyName, active)은 null·빈 문자열이면 400이고,
     * 안내 문구는 null이나 공백 문자열이면 결측(null)으로 정규화한다. itemSeq는 바꿀 수 없다.
     */
    @Transactional
    public Map<String, Object> update(HospitalPrincipal user, long medicationId, JsonNode body) {
        user.require(Role.ADMIN);
        positive(medicationId);
        PatchBody patch = new PatchBody(body,
                Set.of("name", "companyName", "efficacy", "usage", "precautions", "storage", "active"));
        String name = patch.text("name") == null ? null : Values.clean(patch.text("name"), 200);
        String company = patch.text("companyName") == null ? null : Values.clean(patch.text("companyName"), 100);
        Boolean active = patch.bool("active");
        String efficacy = patch.nullableText("efficacy");
        String usage = patch.nullableText("usage");
        String precautions = patch.nullableText("precautions");
        String storage = patch.nullableText("storage");

        // 처방 저장의 같은 약품 FOR SHARE 잠금과 연동한다.
        Map<String, Object> current = db.one(SELECT + " WHERE medicine_id = :id FOR UPDATE", Map.of("id", medicationId));
        db.update("""
                UPDATE medicine
                   SET product_name = :name, company_name = :company, efficacy = :efficacy, usage_text = :usage,
                       precautions = :precautions, storage = :storage, medicine_active = :active
                 WHERE medicine_id = :id
                """, Values.map("name", name == null ? Values.text(current, "product_name") : name,
                "company", company == null ? Values.text(current, "company_name") : company,
                "efficacy", patch.has("efficacy") ? note(efficacy) : current.get("efficacy"),
                "usage", patch.has("usage") ? note(usage) : current.get("usage_text"),
                "precautions", patch.has("precautions") ? note(precautions) : current.get("precautions"),
                "storage", patch.has("storage") ? note(storage) : current.get("storage"),
                "active", active == null ? Boolean.TRUE.equals(current.get("medicine_active")) : active,
                "id", medicationId));
        return view(db.one(SELECT + " WHERE medicine_id = :id", Map.of("id", medicationId)));
    }

    /** 공백만 있는 안내 문구는 결측(null)으로 정규화한다. */
    private static String note(String value) {
        return value == null || value.strip().isEmpty() ? null : value.strip();
    }

    private static void positive(long id) {
        if (id <= 0) { throw ApiException.badRequest(); }
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Values.map("medicationId", Values.id(row, "medicine_id"), "itemSeq", row.get("item_seq"),
                "name", row.get("product_name"), "companyName", row.get("company_name"),
                "efficacy", row.get("efficacy"), "usage", row.get("usage_text"),
                "precautions", row.get("precautions"), "storage", row.get("storage"),
                "active", Boolean.TRUE.equals(row.get("medicine_active")));
    }
}
