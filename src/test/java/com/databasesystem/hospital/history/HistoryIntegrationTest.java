package com.databasesystem.hospital.history;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HistoryIntegrationTest extends IntegrationSupport {
    @Test
    void historyShowsOnlyOwnCompletedVisitsWithDateBoundsAndStableOrder() throws Exception {
        Actor patient = patient(), other = patient("다른환자", LocalDate.of(2000, 1, 1), "01011112222");
        Doctor doctor = doctor();
        long first = visit(patient, doctor, "COMPLETED"), second = visit(patient, doctor, "COMPLETED");
        visit(patient, doctor, "WAITING"); visit(patient, doctor, "IN_PROGRESS"); visit(other, doctor, "COMPLETED");
        getAs("/api/v1/me/visits?from=2026-11-10&to=2026-11-10", patient).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].visitId").value(second))
                .andExpect(jsonPath("$.items[1].visitId").value(first)).andExpect(jsonPath("$.items[0].paymentStatus").value("UNPAID"))
                .andExpect(jsonPath("$.items[0].paymentId").isEmpty());
        getAs("/api/v1/me/visits?from=2026-11-11", patient).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        getAs("/api/v1/me/visits?from=2026-11-11&to=2026-11-10", patient).andExpect(status().isBadRequest());
        getAs("/api/v1/me/visits?patientId=" + other.patientId(), patient).andExpect(status().isBadRequest());
    }

    @Test
    void detailAndPrescriptionDenyOtherOwnersUnfinishedVisitsAndStaff() throws Exception {
        Actor patient = patient(), other = patient("다른환자", LocalDate.of(2000, 1, 1), "01011112222");
        Doctor doctor = doctor(); long completed = visit(patient, doctor, "COMPLETED"), progress = visit(patient, doctor, "IN_PROGRESS");
        long prescription = prescription(progress, medication("테스트약"), "ACTIVE");
        getAs("/api/v1/me/visits/" + completed, other).andExpect(status().isNotFound());
        getAs("/api/v1/me/visits/" + progress, patient).andExpect(status().isNotFound());
        getAs("/api/v1/me/prescriptions/" + prescription, patient).andExpect(status().isNotFound());
        getAs("/api/v1/me/visits/" + completed, staff(Role.CASHIER)).andExpect(status().isForbidden());
        getAs("/api/v1/me/prescriptions/" + prescription, other).andExpect(status().isNotFound());
    }

    @Test
    void cancelledPrescriptionsAndInactiveMedicinesKeepPersonalInstructionsAndNullGuidance() throws Exception {
        Actor patient = patient(), admin = staff(Role.ADMIN), cashier = staff(Role.CASHIER);
        long visit = visit(patient, doctor(), "COMPLETED"); record(visit);
        long medicine = medication("과거약"), prescription = prescription(visit, medicine, "CANCELLED");
        jdbc.update("UPDATE medicine SET medicine_active = false WHERE medicine_id = ?", medicine);
        long fee = fee("FIRST_VISIT", 18000, NOW.minusSeconds(3600), admin);
        long payment = jdbc.queryForObject("""
                INSERT INTO payment(visit_id, fee_id, paid_amount, paid_at, processed_by_account_id)
                VALUES (?, ?, 18000, ?, ?) RETURNING payment_id
                """, Long.class, visit, fee, time(NOW), cashier.accountId());
        getAs("/api/v1/me/visits/" + visit, patient).andExpect(status().isOk())
                .andExpect(jsonPath("$.medicalRecord.diagnosis").value("시연 진단"))
                .andExpect(jsonPath("$.paymentId").value(payment)).andExpect(jsonPath("$.prescriptions[0].status").value("CANCELLED"));
        getAs("/api/v1/me/prescriptions/" + prescription, patient).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.items[0].name").value("과거약"))
                .andExpect(jsonPath("$.items[0].instructions").value("개인 지시"))
                .andExpect(jsonPath("$.items[0].usage").isEmpty()).andExpect(jsonPath("$.items[0].storage").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prescription", Long.class)).isEqualTo(1);
    }

    private long prescription(long visit, long medicine, String status) {
        long id = jdbc.queryForObject("INSERT INTO prescription(visit_id, prescribed_at, prescription_status) VALUES (?, ?, ?) RETURNING prescription_id",
                Long.class, visit, time(NOW), status);
        jdbc.update("INSERT INTO prescription_item VALUES (?, ?, 1.25, '정', 2, 2, 5, 'BEDTIME', '개인 지시')", id, medicine);
        return id;
    }
}
