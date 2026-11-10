CREATE TABLE user_account (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login_id VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('PATIENT', 'RECEPTION', 'DOCTOR', 'CASHIER', 'ADMIN')),
    account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE', 'INACTIVE')),
    token_version BIGINT NOT NULL DEFAULT 0 CHECK (token_version >= 0),
    account_created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_account_login UNIQUE (login_id),
    CHECK (login_id = btrim(login_id) AND login_id <> '')
);

CREATE TABLE patient (
    patient_id BIGINT GENERATED ALWAYS AS IDENTITY (START WITH 100001) PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account,
    patient_name VARCHAR(50) NOT NULL CHECK (btrim(patient_name) <> ''),
    birth_date DATE NOT NULL,
    gender VARCHAR(1) NOT NULL CHECK (gender IN ('M', 'F')),
    phone VARCHAR(20) NOT NULL CHECK (phone ~ '^[0-9]{1,20}$'),
    privacy_agreed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_patient_identity UNIQUE (patient_name, birth_date, phone)
);

CREATE TABLE staff (
    staff_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account,
    staff_name VARCHAR(50) NOT NULL,
    staff_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE department (
    department_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    department_name VARCHAR(50) NOT NULL CHECK (btrim(department_name) <> ''),
    department_active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE UNIQUE INDEX uq_department_name ON department (lower(btrim(department_name)));

CREATE TABLE doctor (
    doctor_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL UNIQUE REFERENCES user_account,
    department_id BIGINT NOT NULL REFERENCES department,
    doctor_name VARCHAR(50) NOT NULL,
    doctor_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE doctor_schedule (
    schedule_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor,
    schedule_date DATE NOT NULL,
    reception_start TIME NOT NULL DEFAULT '09:00',
    closing_time TIME NOT NULL DEFAULT '17:00',
    lunch_start TIME NOT NULL DEFAULT '12:30',
    lunch_end TIME NOT NULL DEFAULT '13:30',
    is_closed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_doctor_schedule UNIQUE (doctor_id, schedule_date),
    CHECK (reception_start = TIME '09:00' AND closing_time = TIME '17:00'
        AND lunch_start = TIME '12:30' AND lunch_end = TIME '13:30')
);

CREATE TABLE reservation (
    reservation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id BIGINT NOT NULL REFERENCES patient,
    schedule_id BIGINT NOT NULL REFERENCES doctor_schedule,
    previous_reservation_id BIGINT REFERENCES reservation,
    planned_reception_at TIMESTAMPTZ NOT NULL,
    reservation_status VARCHAR(20) NOT NULL DEFAULT 'RESERVED'
        CHECK (reservation_status IN ('RESERVED', 'CHECKED_IN', 'CANCELLED')),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reservation_cancelled_at TIMESTAMPTZ,
    CHECK (previous_reservation_id IS NULL OR previous_reservation_id <> reservation_id),
    CHECK ((reservation_status = 'CANCELLED') = (reservation_cancelled_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_reservation_occupied_slot
    ON reservation (schedule_id, planned_reception_at)
    WHERE reservation_status IN ('RESERVED', 'CHECKED_IN');
CREATE INDEX ix_reservation_patient ON reservation (patient_id, planned_reception_at DESC);

CREATE TABLE visit (
    visit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservation,
    checked_in_at TIMESTAMPTZ NOT NULL,
    checked_in_by_staff_id BIGINT NOT NULL REFERENCES staff,
    visit_status VARCHAR(20) NOT NULL DEFAULT 'WAITING'
        CHECK (visit_status IN ('WAITING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    visit_cancelled_at TIMESTAMPTZ,
    cancelled_by_staff_id BIGINT REFERENCES staff,
    cancel_reason VARCHAR(200),
    CONSTRAINT uq_visit_reservation UNIQUE (reservation_id),
    CHECK (completed_at IS NULL OR (started_at IS NOT NULL AND completed_at >= started_at)),
    CHECK ((visit_status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CHECK ((visit_status = 'CANCELLED') = (visit_cancelled_at IS NOT NULL)),
    CHECK (visit_status <> 'IN_PROGRESS' OR started_at IS NOT NULL)
);
CREATE INDEX ix_visit_queue ON visit (visit_status, checked_in_at, visit_id);

CREATE TABLE medical_record (
    record_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit,
    symptoms TEXT NOT NULL CHECK (btrim(symptoms) <> ''),
    diagnosis TEXT NOT NULL CHECK (btrim(diagnosis) <> ''),
    treatment_note TEXT NOT NULL CHECK (btrim(treatment_note) <> ''),
    written_at TIMESTAMPTZ NOT NULL,
    record_updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_record_visit UNIQUE (visit_id)
);

CREATE TABLE medicine (
    medicine_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_seq VARCHAR(20) NOT NULL UNIQUE,
    product_name VARCHAR(200) NOT NULL,
    company_name VARCHAR(100) NOT NULL,
    efficacy TEXT,
    usage_text TEXT,
    precautions TEXT,
    storage TEXT,
    medicine_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE drug_interaction (
    medicine_a_id BIGINT NOT NULL REFERENCES medicine,
    medicine_b_id BIGINT NOT NULL REFERENCES medicine,
    interaction_content TEXT NOT NULL,
    PRIMARY KEY (medicine_a_id, medicine_b_id),
    CHECK (medicine_a_id < medicine_b_id)
);

CREATE TABLE age_contraindication (
    medicine_id BIGINT NOT NULL REFERENCES medicine,
    age_seq INTEGER NOT NULL CHECK (age_seq > 0),
    age_limit SMALLINT NOT NULL CHECK (age_limit >= 0),
    age_condition VARCHAR(10) NOT NULL CHECK (age_condition IN ('LT', 'LTE')),
    age_content TEXT NOT NULL,
    PRIMARY KEY (medicine_id, age_seq),
    CONSTRAINT uq_age_condition UNIQUE (medicine_id, age_limit, age_condition)
);

CREATE TABLE prescription (
    prescription_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit,
    prescribed_at TIMESTAMPTZ NOT NULL,
    prescription_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (prescription_status IN ('ACTIVE', 'CANCELLED'))
);
CREATE INDEX ix_prescription_visit ON prescription (visit_id, prescribed_at);

CREATE TABLE prescription_item (
    prescription_id BIGINT NOT NULL REFERENCES prescription,
    medicine_id BIGINT NOT NULL REFERENCES medicine,
    dose_amount NUMERIC(6,2) NOT NULL CHECK (dose_amount > 0),
    dose_unit VARCHAR(10) NOT NULL CHECK (btrim(dose_unit) <> ''),
    times_per_day SMALLINT NOT NULL CHECK (times_per_day > 0),
    interval_days SMALLINT NOT NULL CHECK (interval_days > 0),
    total_days SMALLINT NOT NULL CHECK (total_days > 0),
    timing VARCHAR(20) NOT NULL CHECK (timing IN ('BEFORE_MEAL', 'AFTER_MEAL', 'BEDTIME')),
    directions VARCHAR(200) NOT NULL CHECK (btrim(directions) <> ''),
    PRIMARY KEY (prescription_id, medicine_id)
);

CREATE TABLE contraindication_override (
    prescription_id BIGINT NOT NULL REFERENCES prescription,
    override_seq INTEGER NOT NULL CHECK (override_seq > 0),
    medicine_id BIGINT NOT NULL REFERENCES medicine,
    contra_type VARCHAR(20) NOT NULL CHECK (contra_type IN ('INTERACTION', 'AGE')),
    related_medicine_id BIGINT REFERENCES medicine,
    contra_content TEXT NOT NULL,
    override_reason TEXT NOT NULL CHECK (btrim(override_reason) <> ''),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (prescription_id, override_seq)
);

CREATE TABLE consultation_fee (
    fee_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fee_type VARCHAR(20) NOT NULL CHECK (fee_type IN ('FIRST_VISIT', 'FOLLOW_UP')),
    fee_amount INTEGER NOT NULL CHECK (fee_amount > 0),
    effective_from TIMESTAMPTZ NOT NULL,
    set_by_staff_id BIGINT NOT NULL REFERENCES staff,
    CONSTRAINT uq_fee_effective UNIQUE (fee_type, effective_from)
);

CREATE TABLE payment (
    payment_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit,
    fee_id BIGINT NOT NULL REFERENCES consultation_fee,
    paid_amount INTEGER NOT NULL CHECK (paid_amount > 0),
    paid_at TIMESTAMPTZ NOT NULL,
    processed_by_staff_id BIGINT NOT NULL REFERENCES staff,
    CONSTRAINT uq_payment_visit UNIQUE (visit_id)
);

CREATE TABLE patient_demographic_audit (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id BIGINT NOT NULL REFERENCES patient,
    changed_by_staff_id BIGINT NOT NULL REFERENCES staff,
    previous_birth_date DATE NOT NULL,
    new_birth_date DATE NOT NULL,
    previous_gender VARCHAR(1) NOT NULL,
    new_gender VARCHAR(1) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);
