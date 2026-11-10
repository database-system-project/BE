package com.databasesystem.hospital.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;

public final class Query {
    private Query() { }

    public static LocalDate date(Map<String, String> parameters, String key) {
        if (!parameters.containsKey(key)) { return null; }
        try { return LocalDate.parse(parameters.get(key)); }
        catch (DateTimeParseException exception) { throw ApiException.badRequest(); }
    }

    public static Long id(Map<String, String> parameters, String key) {
        if (!parameters.containsKey(key)) { return null; }
        try {
            long value = Long.parseLong(parameters.get(key));
            if (value <= 0) { throw ApiException.badRequest(); }
            return value;
        } catch (NumberFormatException exception) { throw ApiException.badRequest(); }
    }

    public static String choice(Map<String, String> parameters, String key, String... choices) {
        String value = parameters.get(key);
        if (value != null && Arrays.stream(choices).noneMatch(value::equals)) {
            throw ApiException.badRequest();
        }
        return value;
    }

    public static void range(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) { throw ApiException.badRequest(); }
    }
}
