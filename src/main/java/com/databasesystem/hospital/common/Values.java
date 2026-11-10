package com.databasesystem.hospital.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Values {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private Values() { }

    public static Map<String, Object> map(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value pairs required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    public static long id(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    public static Long nullableId(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : id(row, key);
    }

    public static String text(Map<String, Object> row, String key) {
        return (String) row.get(key);
    }

    public static LocalDate date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof java.sql.Date date ? date.toLocalDate() : (LocalDate) value;
    }

    public static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) { return null; }
        if (value instanceof Timestamp timestamp) { return timestamp.toInstant(); }
        if (value instanceof OffsetDateTime time) { return time.toInstant(); }
        return (Instant) value;
    }

    public static OffsetDateTime time(Map<String, Object> row, String key) {
        Instant value = instant(row, key);
        return value == null ? null : value.atZone(SEOUL).toOffsetDateTime();
    }

    public static String clean(String value, int maximum) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > maximum) {
            throw ApiException.badRequest();
        }
        return value.strip();
    }

    public static String phone(String value) {
        String normalized = value == null ? "" : value.replaceAll("[-\\s]", "");
        if (!normalized.matches("[0-9]{1,20}")) { throw ApiException.badRequest(); }
        return normalized;
    }
}
