package com.databasesystem.hospital.billing;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BillingIntegrationTest extends IntegrationSupport {
    @Test
    void changedFeeRequiresConfirmationAndPaidAmountSurvivesLaterChanges() throws Exception {
        Actor patient = patient(), cashier = staff(Role.CASHIER), admin = staff(Role.ADMIN);
        long visit = visit(patient, doctor(), "COMPLETED");
        long old = fee("FIRST_VISIT", 18000, NOW.minusSeconds(3600), admin);
        getAs("/api/v1/billing/visits/" + visit, cashier).andExpect(status().isOk()).andExpect(jsonPath("$.feeId").value(old));
        long latest = fee("FIRST_VISIT", 20000, NOW.minusSeconds(60), admin);
        postAs(payPath(visit), cashier, Map.of("feeId", old)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_CHANGED")).andExpect(jsonPath("$.details.feeId").value(latest));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isZero();
        String response = postAs(payPath(visit), cashier, Map.of("feeId", latest)).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(20000)).andReturn().getResponse().getContentAsString();
        long payment = mapper.readTree(response).path("paymentId").asLong();
        fee("FIRST_VISIT", 25000, NOW, admin);
        postAs(payPath(visit), cashier, Map.of("feeId", old)).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(payment)).andExpect(jsonPath("$.feeId").value(latest))
                .andExpect(jsonPath("$.amount").value(20000));
        getAs("/api/v1/payments/" + payment + "/receipt", patient).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNumber").value(payment)).andExpect(jsonPath("$.amount").value(20000));
    }

    @Test
    void concurrentPaymentsReturnOneSavedPayment() throws Exception {
        Actor patient = patient(), cashier = staff(Role.CASHIER);
        long visit = visit(patient, doctor(), "COMPLETED");
        long fee = fee("FIRST_VISIT", 18000, NOW.minusSeconds(3600), staff(Role.ADMIN));
        CountDownLatch latch = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(1, 2).stream().map(i -> executor.submit(() -> {
                latch.await();
                String response = postAs(payPath(visit), cashier, Map.of("feeId", fee)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                return mapper.readTree(response).path("paymentId").asLong();
            })).toList();
            latch.countDown();
            assertThat(results.get(0).get(10, TimeUnit.SECONDS)).isEqualTo(results.get(1).get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT processed_by_account_id FROM payment", Long.class)).isEqualTo(cashier.accountId());
    }

    @Test
    void firstAndFollowUpUseAllCompletedVisitsEvenUnpaidAndDifferentDoctors() throws Exception {
        Actor patient = patient(), cashier = staff(Role.CASHIER), admin = staff(Role.ADMIN);
        long first = visit(patient, doctor(), "COMPLETED"), second = visit(patient, doctor(), "COMPLETED");
        fee("FIRST_VISIT", 18000, NOW.minusSeconds(3600), admin);
        fee("FOLLOW_UP", 12000, NOW.minusSeconds(3600), admin);
        fee("FOLLOW_UP", 30000, NOW.plusSeconds(1), admin);
        getAs("/api/v1/billing/visits/" + first, cashier).andExpect(status().isOk())
                .andExpect(jsonPath("$.feeType").value("FIRST_VISIT"));
        getAs("/api/v1/billing/visits/" + second, cashier).andExpect(status().isOk())
                .andExpect(jsonPath("$.feeType").value("FOLLOW_UP")).andExpect(jsonPath("$.amount").value(12000));
        getAs("/api/v1/billing/visits?from=2026-11-09&to=2026-11-10&paymentStatus=UNPAID", cashier)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].visitId").value(first))
                .andExpect(jsonPath("$.items[1].visitId").value(second));
        getAs("/api/v1/billing/visits?from=2026-11-11&to=2026-11-10", cashier).andExpect(status().isBadRequest());
    }

    @Test
    void receiptsAreOwnedAndPrintingRequiresPaymentAndOnlyActivePrescriptions() throws Exception {
        Actor patient = patient(), other = patient("다른환자", LocalDate.of(1990, 1, 1), "01022223333");
        Actor cashier = staff(Role.CASHIER); Doctor doctor = doctor(); long visit = visit(patient, doctor, "COMPLETED");
        getAs("/api/v1/billing/visits/" + visit + "/prescription-print", cashier).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REQUIRED"));
        long fee = fee("FIRST_VISIT", 18000, NOW.minusSeconds(3600), staff(Role.ADMIN));
        String response = postAs(payPath(visit), cashier, Map.of("feeId", fee)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long payment = mapper.readTree(response).path("paymentId").asLong();
        getAs("/api/v1/payments/" + payment + "/receipt", other).andExpect(status().isNotFound());
        getAs("/api/v1/payments/" + payment + "/receipt", doctor.actor()).andExpect(status().isForbidden());
        getAs("/api/v1/billing/visits/" + visit + "/prescription-print", cashier).andExpect(status().isOk())
                .andExpect(jsonPath("$.canPrint").value(false));
        long medicine = medication("출력약");
        long rx = jdbc.queryForObject("INSERT INTO prescription(visit_id, prescribed_at) VALUES (?, ?) RETURNING prescription_id",
                Long.class, visit, time(NOW));
        jdbc.update("INSERT INTO prescription_item VALUES (?, ?, 1, '정', 2, 1, 3, 'AFTER_MEAL', '복용 안내')", rx, medicine);
        jdbc.update("INSERT INTO prescription(visit_id, prescribed_at, prescription_status) VALUES (?, ?, 'CANCELLED')", visit, time(NOW));
        getAs("/api/v1/billing/visits/" + visit + "/prescription-print", cashier).andExpect(status().isOk())
                .andExpect(jsonPath("$.canPrint").value(true)).andExpect(jsonPath("$.prescriptions.length()").value(1))
                .andExpect(jsonPath("$.prescriptions[0].items[0].medicationName").value("출력약"))
                .andExpect(jsonPath("$.prescriptions[0].items[0].intervalDays").value(1));
    }

    @Test
    void unfinishedVisitsAndMissingFeesCannotCreatePayments() throws Exception {
        Actor patient = patient(), cashier = staff(Role.CASHIER); Doctor doctor = doctor();
        long waiting = visit(patient, doctor, "WAITING"), completed = visit(patient, doctor, "COMPLETED");
        postAs(payPath(waiting), cashier, Map.of("feeId", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VISIT_NOT_COMPLETED"));
        postAs(payPath(completed), cashier, Map.of("feeId", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_NOT_CONFIGURED"));
        getAs("/api/v1/billing/visits", patient).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isZero();
    }

    private String payPath(long visit) { return "/api/v1/billing/visits/" + visit + "/payments"; }
}
