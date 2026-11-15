package com.databasesystem.hospital.auth;

import com.databasesystem.hospital.common.ApiException;

import java.util.Arrays;

public record HospitalPrincipal(long accountId, String loginId, String name, Role role,
                                Long patientId, Long doctorId, Long staffId, long tokenVersion) {
    public void require(Role... roles) {
        if (Arrays.stream(roles).noneMatch(role::equals)) { throw ApiException.forbidden(); }
    }

    public long patient() {
        require(Role.PATIENT);
        if (patientId == null) { throw ApiException.forbidden(); }
        return patientId;
    }

    public long doctor() {
        require(Role.DOCTOR);
        if (doctorId == null) { throw ApiException.forbidden(); }
        return doctorId;
    }
}
