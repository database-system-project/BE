package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Set;

/**
 * PATCH 본문을 검사한다. 생략한 필드는 유지하고, 보냈지만 null이거나 형식이 다르면 400이다.
 * (record로 받으면 "생략"과 "null"을 구분할 수 없어서 JsonNode로 받는다.)
 */
final class PatchBody {
    private final JsonNode body;

    PatchBody(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || body.isEmpty()) { throw ApiException.badRequest(); }
        for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
            if (!allowed.contains(names.next())) { throw ApiException.badRequest(); }
        }
        this.body = body;
    }

    String text(String key) {
        if (!body.has(key)) { return null; }
        if (!body.get(key).isTextual()) { throw ApiException.badRequest(); }
        return body.get(key).asText();
    }

    Boolean bool(String key) {
        if (!body.has(key)) { return null; }
        if (!body.get(key).isBoolean()) { throw ApiException.badRequest(); }
        return body.get(key).asBoolean();
    }

    Long id(String key) {
        if (!body.has(key)) { return null; }
        JsonNode node = body.get(key);
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) { throw ApiException.badRequest(); }
        return node.asLong();
    }
}
