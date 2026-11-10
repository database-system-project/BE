package com.databasesystem.hospital.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public final class Dates {
    private Dates() { }

    public static LocalDate parse(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) { throw ApiException.badRequest(); }
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) { throw ApiException.badRequest(); }
            return date;
        } catch (DateTimeParseException exception) { throw ApiException.badRequest(); }
    }
}
