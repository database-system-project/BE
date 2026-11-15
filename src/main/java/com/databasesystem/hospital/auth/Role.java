package com.databasesystem.hospital.auth;

public enum Role {
    PATIENT, RECEPTION, DOCTOR, CASHIER, ADMIN;

    public boolean isStaff() {
        return this == RECEPTION || this == CASHIER || this == ADMIN;
    }
}
