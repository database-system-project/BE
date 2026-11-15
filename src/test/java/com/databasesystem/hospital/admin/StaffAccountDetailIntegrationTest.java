package com.databasesystem.hospital.admin;

import com.databasesystem.hospital.auth.Role;
import com.databasesystem.hospital.support.IntegrationSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffAccountDetailIntegrationTest extends IntegrationSupport {
    private static final String PATH = "/api/v1/admin/staff-accounts/";

    @Test
    void doctorAccountsAreNotStaffAccounts() throws Exception {
        Actor admin = staff(Role.ADMIN);
        long unlinkedDoctor = account("unlinked_doctor", Role.DOCTOR);
        for (long accountId : List.of(doctor().actor().accountId(), unlinkedDoctor)) {
            getAs(PATH + accountId, admin).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void staffDetailReturnsStaffIdAndOmitsDoctorId() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor cashier = staff(Role.CASHIER);
        getAs(PATH + cashier.accountId(), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(cashier.accountId()))
                .andExpect(jsonPath("$.loginId").value(cashier.loginId()))
                .andExpect(jsonPath("$.name").value("테스트직원"))
                .andExpect(jsonPath("$.role").value("CASHIER"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.staffId").value(cashier.staffId()))
                .andExpect(jsonPath("$.doctorId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void inactiveStaffIsReportedAsInactive() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor target = staff(Role.RECEPTION);
        jdbc.update("UPDATE staff SET staff_active = FALSE WHERE staff_id = ?", target.staffId());
        getAs(PATH + target.accountId(), admin).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        jdbc.update("UPDATE staff SET staff_active = TRUE WHERE staff_id = ?", target.staffId());
        jdbc.update("UPDATE user_account SET account_status = 'INACTIVE' WHERE account_id = ?", target.accountId());
        getAs(PATH + target.accountId(), admin).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void patientAndMissingAccountsAreNotFound() throws Exception {
        Actor admin = staff(Role.ADMIN);
        Actor patient = patient();
        for (long id : List.of(patient.accountId(), 999_999_999L)) {
            getAs(PATH + id, admin).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("대상이 없거나 접근할 수 없는 리소스입니다."));
        }
    }

    @Test
    void invalidAccountIdIsRejected() throws Exception {
        Actor admin = staff(Role.ADMIN);
        for (String id : List.of("0", "-1", "abc", "1.5")) {
            getAs(PATH + id, admin).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void onlyAdminsCanViewDetail() throws Exception {
        Actor target = staff(Role.CASHIER);
        for (Actor other : List.of(staff(Role.RECEPTION), staff(Role.CASHIER), doctor().actor(), patient())) {
            getAs(PATH + target.accountId(), other).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get(PATH + target.accountId())).andExpect(status().isUnauthorized());
    }
}
