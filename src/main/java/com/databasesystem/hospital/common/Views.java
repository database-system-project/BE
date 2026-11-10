package com.databasesystem.hospital.common;

import java.util.Map;

import static com.databasesystem.hospital.common.Values.SEOUL;
import static com.databasesystem.hospital.common.Values.date;
import static com.databasesystem.hospital.common.Values.id;
import static com.databasesystem.hospital.common.Values.instant;
import static com.databasesystem.hospital.common.Values.map;
import static com.databasesystem.hospital.common.Values.text;
import static com.databasesystem.hospital.common.Values.time;

public final class Views {
    private Views() { }

    public static Map<String, Object> patient(Map<String, Object> row) {
        return map("patientId", id(row, "patient_id"), "name", text(row, "patient_name"),
                "birthDate", date(row, "birth_date"), "gender", text(row, "gender"), "phone", text(row, "phone"));
    }

    public static Map<String, Object> visit(Map<String, Object> row) {
        long visitId = id(row, "visit_id");
        String day = instant(row, "checked_in_at").atZone(SEOUL).toLocalDate().toString().replace("-", "");
        return map("visitId", visitId, "appointmentId", id(row, "reservation_id"),
                "patientId", id(row, "patient_id"), "patientName", text(row, "patient_name"),
                "doctorId", id(row, "doctor_id"), "departmentName", text(row, "department_name"),
                "receptionNumber", visitId, "receptionCode", "V" + day + "-" + visitId,
                "status", text(row, "visit_status"), "checkedInAt", time(row, "checked_in_at"),
                "startedAt", time(row, "started_at"), "completedAt", time(row, "completed_at"));
    }

    public static Map<String, Object> record(Map<String, Object> row) {
        return map("recordId", id(row, "record_id"), "visitId", id(row, "visit_id"),
                "symptoms", text(row, "symptoms"), "diagnosis", text(row, "diagnosis"),
                "treatmentNote", text(row, "treatment_note"), "writtenAt", time(row, "written_at"),
                "updatedAt", time(row, "record_updated_at"));
    }

    public static Map<String, Object> medication(Map<String, Object> row) {
        return map("medicationId", id(row, "medicine_id"), "itemSeq", text(row, "item_seq"),
                "name", text(row, "product_name"), "companyName", text(row, "company_name"),
                "efficacy", row.get("efficacy"), "usage", row.get("usage_text"),
                "precautions", row.get("precautions"), "storage", row.get("storage"),
                "active", row.get("medicine_active"));
    }

    public static Map<String, Object> prescriptionItem(Map<String, Object> row) {
        return map("medicationId", id(row, "medicine_id"), "dosePerIntake", row.get("dose_amount"),
                "doseUnit", text(row, "dose_unit"), "timesPerDay", row.get("times_per_day"),
                "intervalDays", row.get("interval_days"), "durationDays", row.get("total_days"),
                "timing", text(row, "timing"), "instructions", text(row, "directions"));
    }
}
