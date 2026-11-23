#!/usr/bin/env python3
"""Generate an explicit, one-time PostgreSQL demo seed for the current V2 schema."""
import argparse
import hashlib
import os
import re
import secrets
from datetime import date, datetime, time
from pathlib import Path
from zoneinfo import ZoneInfo


def quoted(value):
    return "'" + str(value).replace("'", "''") + "'"


def password_hash(password):
    # Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(): salt + SHA256 hash.
    salt = secrets.token_bytes(16)
    return (salt + hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 310000, 32)).hex()


def generate(day, start, password):
    source = Path(__file__).with_name("local-test-seed.sql").read_text()
    base = re.findall(r"VALUES \('([^']+)', '([0-9a-f]+)', '([A-Z]+)'\)", source)
    if len(base) != 6:
        raise ValueError("Expected the six original accounts in local-test-seed.sql")
    accounts = [(login, saved if password == "Test1234!" else password_hash(password), role)
                for login, saved, role in base]
    accounts.extend((login, password_hash(password), "PATIENT")
                    for login in ("patient02", "patient03"))
    values = ",\n".join(
        f"    ({quoted(login)}, {quoted(encoded)}, {quoted(role)})"
        for login, encoded, role in accounts
    )
    return TEMPLATE.replace("__ACCOUNTS__", values).replace(
        "__DAY__", quoted(day.isoformat())
    ).replace("__START__", quoted(start.strftime("%H:%M")))


TEMPLATE = """-- GENERATED: synthetic hospital demo, current PostgreSQL V2 schema.
-- Apply only AFTER Flyway V1, V2, R__seed_medicine.
-- psql -X -v ON_ERROR_STOP=1 -f <this file>
-- No DELETE/TRUNCATE/reset; refuses a database with any existing accounts.
BEGIN;
SET LOCAL standard_conforming_strings = on;
SET LOCAL TIME ZONE 'Asia/Seoul';
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
SELECT set_config('oncare.demo_day', __DAY__, true);
SELECT set_config('oncare.demo_start', __START__, true);

DO $demo$
DECLARE
    day DATE := current_setting('oncare.demo_day')::date;
    t TIME := current_setting('oncare.demo_start')::time;
    history_day DATE;
    next_day DATE;
    registered_at TIMESTAMPTZ;
    anchor TIMESTAMPTZ;
    pair_a BIGINT; pair_b BIGINT; pair_text TEXT;
    age_med BIGINT; age_text TEXT;
    admin_id BIGINT; desk_id BIGINT; cashier_id BIGINT;
    patient1 BIGINT; patient2 BIGINT; child BIGINT;
    doc1 BIGINT; doc2 BIGINT;
    fee_first BIGINT; fee_follow BIGINT;
    appointment BIGINT; prior_appointment BIGINT;
    v BIGINT; rx BIGINT;
BEGIN
    -- Lock the empty account table throughout the seed transaction.
    LOCK TABLE user_account IN EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM user_account) THEN
        RAISE EXCEPTION 'Demo seed requires an empty account table. Existing data was preserved.';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'consultation_fee'
          AND column_name = 'set_by_account_id'
    ) THEN
        RAISE EXCEPTION 'Apply Flyway V2 before this seed.';
    END IF;
    IF EXISTS (SELECT 1 FROM consultation_fee) THEN
        RAISE EXCEPTION 'Existing fee history found; no rows were changed.';
    END IF;
    anchor := (day + t) AT TIME ZONE 'Asia/Seoul';
    history_day := day - CASE WHEN EXTRACT(ISODOW FROM day - 1) = 7 THEN 2 ELSE 1 END;
    next_day := day + CASE WHEN EXTRACT(ISODOW FROM day + 1) = 7 THEN 2 ELSE 1 END;
    registered_at := (history_day - 1)::timestamp AT TIME ZONE 'Asia/Seoul';
    -- Reuse real public reference data; never invent medicines or DUR rules.
    SELECT d.medicine_a_id, d.medicine_b_id, d.interaction_content
      INTO pair_a, pair_b, pair_text
      FROM drug_interaction d
      JOIN medicine a ON a.medicine_id = d.medicine_a_id
      JOIN medicine b ON b.medicine_id = d.medicine_b_id
     WHERE a.medicine_active AND b.medicine_active
       AND NOT EXISTS (SELECT 1 FROM age_contraindication ac
                        WHERE ac.medicine_id IN (a.medicine_id, b.medicine_id)
                          AND ac.age_limit >= 30)
     ORDER BY (a.efficacy IS NULL), (b.efficacy IS NULL), d.medicine_a_id, d.medicine_b_id
     LIMIT 1;
    SELECT ac.medicine_id, ac.age_content INTO age_med, age_text
      FROM age_contraindication ac JOIN medicine m USING (medicine_id)
     WHERE m.medicine_active AND ac.age_limit BETWEEN 12 AND 18
     ORDER BY (m.efficacy IS NULL), ac.medicine_id, ac.age_seq LIMIT 1;
    IF pair_a IS NULL OR age_med IS NULL THEN
        RAISE EXCEPTION 'Public medicine/DUR reference seed is missing required demo rules.';
    END IF;

    INSERT INTO user_account(login_id, password_hash, role) VALUES
__ACCOUNTS__;
    UPDATE user_account SET account_created_at = registered_at;
    SELECT account_id INTO admin_id FROM user_account WHERE login_id = 'admin01';
    SELECT account_id INTO desk_id FROM user_account WHERE login_id = 'desk01';
    SELECT account_id INTO cashier_id FROM user_account WHERE login_id = 'cashier01';
    INSERT INTO staff(account_id, staff_name) VALUES
        (admin_id, '관리자'), (desk_id, '원무 직원'), (cashier_id, '수납 담당자');
    INSERT INTO department(department_name) VALUES ('내과'), ('피부과') ON CONFLICT DO NOTHING;
    IF (SELECT count(*) FROM department
         WHERE department_name IN ('내과', '피부과') AND department_active) <> 2 THEN
        RAISE EXCEPTION 'Active demo departments could not be resolved.';
    END IF;
    INSERT INTO doctor(account_id, department_id, doctor_name)
        SELECT a.account_id, d.department_id, '이정훈'
          FROM user_account a, department d
         WHERE a.login_id = 'doctor01' AND d.department_name = '내과'
        RETURNING doctor_id INTO doc1;
    INSERT INTO doctor(account_id, department_id, doctor_name)
        SELECT a.account_id, d.department_id, '김민서'
          FROM user_account a, department d
         WHERE a.login_id = 'doctor02' AND d.department_name = '피부과'
        RETURNING doctor_id INTO doc2;
    -- Original rule: today + 13 days, Sundays excluded. Add one historical day.
    INSERT INTO doctor_schedule(doctor_id, schedule_date)
        SELECT d.id, dates.dt FROM (VALUES (doc1), (doc2)) d(id)
        CROSS JOIN (SELECT day + n AS dt FROM generate_series(0, 13) n
                    UNION SELECT history_day) dates
        WHERE EXTRACT(ISODOW FROM dates.dt) <> 7;
    INSERT INTO patient(account_id, patient_name, birth_date, gender, phone, privacy_agreed_at)
        SELECT account_id, '테스트환자', DATE '1990-01-01', 'F', '01000000001', registered_at
          FROM user_account WHERE login_id = 'patient01' RETURNING patient_id INTO patient1;
    INSERT INTO patient(account_id, patient_name, birth_date, gender, phone, privacy_agreed_at)
        SELECT account_id, '박서준', day - INTERVAL '35 years', 'M', '00000000002', registered_at
          FROM user_account WHERE login_id = 'patient02' RETURNING patient_id INTO patient2;
    INSERT INTO patient(account_id, patient_name, birth_date, gender, phone, privacy_agreed_at)
        SELECT account_id, '김민준', day - INTERVAL '10 years', 'M', '00000000003', registered_at
          FROM user_account WHERE login_id = 'patient03' RETURNING patient_id INTO child;
    INSERT INTO consultation_fee(fee_type, fee_amount, effective_from, set_by_account_id)
        VALUES ('FIRST_VISIT', 18000, TIMESTAMPTZ '2026-01-01 00:00:00+09', admin_id)
        RETURNING fee_id INTO fee_first;
    INSERT INTO consultation_fee(fee_type, fee_amount, effective_from, set_by_account_id)
        VALUES ('FOLLOW_UP', 15000, TIMESTAMPTZ '2026-01-01 00:00:00+09', admin_id)
        RETURNING fee_id INTO fee_follow;

    -- 1. Adult1 yesterday: first visit, paid, still taking medicine A (30 days).
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, reservation_status, reserved_at)
        SELECT patient1, schedule_id, (history_day + TIME '09:00') AT TIME ZONE 'Asia/Seoul', 'CHECKED_IN', registered_at
          FROM doctor_schedule WHERE doctor_id = doc1 AND schedule_date = history_day
        RETURNING reservation_id INTO appointment;
    INSERT INTO visit(reservation_id, checked_in_at, checked_in_by_account_id, visit_status, started_at, completed_at)
        VALUES (appointment, (history_day + TIME '09:00') AT TIME ZONE 'Asia/Seoul', desk_id, 'COMPLETED',
                (history_day + TIME '09:05') AT TIME ZONE 'Asia/Seoul', (history_day + TIME '09:20') AT TIME ZONE 'Asia/Seoul')
        RETURNING visit_id INTO v;
    INSERT INTO medical_record(visit_id, symptoms, diagnosis, treatment_note, written_at, record_updated_at)
        SELECT v, '가상 증상', '시연 진단', '현재 복용약 비교용 가상 기록', completed_at, completed_at FROM visit WHERE visit_id = v;
    INSERT INTO prescription(visit_id, prescribed_at)
        SELECT v, completed_at - INTERVAL '5 minutes' FROM visit WHERE visit_id = v RETURNING prescription_id INTO rx;
    INSERT INTO prescription_item VALUES (rx, pair_a, 1, '정', 1, 1, 30, 'AFTER_MEAL', '시연용 가상 투약값');
    INSERT INTO payment(visit_id, fee_id, paid_amount, paid_at, processed_by_account_id)
        SELECT v, fee_first, 18000, completed_at + INTERVAL '5 minutes', cashier_id FROM visit WHERE visit_id = v;

    -- 2. Adult1 today: follow-up, unpaid, interacting B with a saved exception.
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, reservation_status, reserved_at)
        SELECT patient1, schedule_id, anchor - INTERVAL '90 minutes', 'CHECKED_IN', anchor - INTERVAL '1 day'
          FROM doctor_schedule WHERE doctor_id = doc1 AND schedule_date = day
        RETURNING reservation_id INTO appointment;
    INSERT INTO visit(reservation_id, checked_in_at, checked_in_by_account_id, visit_status, started_at, completed_at)
        VALUES (appointment, anchor - INTERVAL '90 minutes', desk_id, 'COMPLETED', anchor - INTERVAL '85 minutes', anchor - INTERVAL '75 minutes')
        RETURNING visit_id INTO v;
    INSERT INTO medical_record(visit_id, symptoms, diagnosis, treatment_note, written_at, record_updated_at)
        VALUES (v, '가상 증상', '시연 재진', '병용금기 예외 기록 조회용', anchor - INTERVAL '80 minutes', anchor - INTERVAL '80 minutes');
    INSERT INTO prescription(visit_id, prescribed_at) VALUES (v, anchor - INTERVAL '80 minutes') RETURNING prescription_id INTO rx;
    INSERT INTO prescription_item VALUES (rx, pair_b, 1, '정', 1, 1, 3, 'AFTER_MEAL', '시연용 가상 투약값');
    INSERT INTO contraindication_override VALUES
        (rx, 1, pair_b, 'INTERACTION', pair_a, pair_text, '시연 전용 가상 예외 기록. 실제 복용 지시가 아님.', anchor - INTERVAL '80 minutes');

    -- 3. Adult2 today: first visit, unpaid, ready for cashier payment.
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, reservation_status, reserved_at)
        SELECT patient2, schedule_id, anchor - INTERVAL '60 minutes', 'CHECKED_IN', anchor - INTERVAL '1 day'
          FROM doctor_schedule WHERE doctor_id = doc1 AND schedule_date = day RETURNING reservation_id INTO appointment;
    INSERT INTO visit(reservation_id, checked_in_at, checked_in_by_account_id, visit_status, started_at, completed_at)
        VALUES (appointment, anchor - INTERVAL '60 minutes', desk_id, 'COMPLETED', anchor - INTERVAL '55 minutes', anchor - INTERVAL '45 minutes')
        RETURNING visit_id INTO v;
    INSERT INTO medical_record(visit_id, symptoms, diagnosis, treatment_note, written_at, record_updated_at)
        VALUES (v, '가상 증상', '시연 초진', '초진 수납 확인용', anchor - INTERVAL '50 minutes', anchor - INTERVAL '50 minutes');
    INSERT INTO prescription(visit_id, prescribed_at) VALUES (v, anchor - INTERVAL '50 minutes') RETURNING prescription_id INTO rx;
    INSERT INTO prescription_item VALUES (rx, pair_a, 1, '정', 1, 1, 3, 'AFTER_MEAL', '시연용 가상 투약값');

    -- 4. Child yesterday: paid, age exception and medication guide to inspect.
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, reservation_status, reserved_at)
        SELECT child, schedule_id, (history_day + TIME '09:30') AT TIME ZONE 'Asia/Seoul', 'CHECKED_IN', registered_at
          FROM doctor_schedule WHERE doctor_id = doc1 AND schedule_date = history_day RETURNING reservation_id INTO appointment;
    INSERT INTO visit(reservation_id, checked_in_at, checked_in_by_account_id, visit_status, started_at, completed_at)
        VALUES (appointment, (history_day + TIME '09:30') AT TIME ZONE 'Asia/Seoul', desk_id, 'COMPLETED',
                (history_day + TIME '09:35') AT TIME ZONE 'Asia/Seoul', (history_day + TIME '09:50') AT TIME ZONE 'Asia/Seoul')
        RETURNING visit_id INTO v;
    INSERT INTO medical_record(visit_id, symptoms, diagnosis, treatment_note, written_at, record_updated_at)
        SELECT v, '가상 증상', '소아 시연 진단', '연령금기 예외 조회용', completed_at, completed_at FROM visit WHERE visit_id = v;
    INSERT INTO prescription(visit_id, prescribed_at)
        SELECT v, completed_at - INTERVAL '5 minutes' FROM visit WHERE visit_id = v RETURNING prescription_id INTO rx;
    INSERT INTO prescription_item VALUES (rx, age_med, 1, '정', 1, 1, 3, 'AFTER_MEAL', '시연용 가상 투약값');
    INSERT INTO contraindication_override
        SELECT rx, row_number() OVER (ORDER BY age_seq)::integer, age_med, 'AGE', NULL, age_content,
               '시연 전용 가상 예외 기록. 실제 복용 지시가 아님.', (history_day + TIME '09:45') AT TIME ZONE 'Asia/Seoul'
          FROM age_contraindication WHERE medicine_id = age_med
           AND ((age_condition = 'LT' AND 9 < age_limit) OR (age_condition = 'LTE' AND 9 <= age_limit));
    INSERT INTO payment(visit_id, fee_id, paid_amount, paid_at, processed_by_account_id)
        SELECT v, fee_first, 18000, completed_at + INTERVAL '5 minutes', cashier_id FROM visit WHERE visit_id = v;

    -- 5. Adult2 waiting today: doctor start/record/prescription/complete workflow.
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, reservation_status, reserved_at)
        SELECT patient2, schedule_id, anchor, 'CHECKED_IN', anchor - INTERVAL '1 day'
          FROM doctor_schedule WHERE doctor_id = doc1 AND schedule_date = day RETURNING reservation_id INTO appointment;
    INSERT INTO visit(reservation_id, checked_in_at, checked_in_by_account_id)
        VALUES (appointment, anchor - INTERVAL '5 minutes', desk_id);
    -- 6. Child reserved today: receptionist check-in and age warning workflow.
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, reserved_at)
        SELECT child, schedule_id, anchor + INTERVAL '30 minutes', anchor - INTERVAL '1 day'
          FROM doctor_schedule WHERE doctor_id = doc1 AND schedule_date = day;
    -- 7. Cancelled before its slot and 8. replacement on the next open day.
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, reservation_status, reserved_at, reservation_cancelled_at)
        SELECT patient1, schedule_id, (day + TIME '09:00') AT TIME ZONE 'Asia/Seoul', 'CANCELLED',
               anchor - INTERVAL '1 day', (day + TIME '08:00') AT TIME ZONE 'Asia/Seoul'
          FROM doctor_schedule WHERE doctor_id = doc2 AND schedule_date = day RETURNING reservation_id INTO prior_appointment;
    INSERT INTO reservation(patient_id, schedule_id, planned_reception_at, previous_reservation_id, reserved_at)
        SELECT patient1, schedule_id, (next_day + TIME '10:30') AT TIME ZONE 'Asia/Seoul', prior_appointment,
               (day + TIME '08:00') AT TIME ZONE 'Asia/Seoul'
          FROM doctor_schedule WHERE doctor_id = doc2 AND schedule_date = next_day;
END
$demo$;
COMMIT;

-- IDs are generated; no assumptions about sequence starts or reference-data IDs.
SELECT a.login_id, a.role, p.patient_id, d.doctor_id, s.staff_id
  FROM user_account a LEFT JOIN patient p USING(account_id)
  LEFT JOIN doctor d USING(account_id) LEFT JOIN staff s USING(account_id)
 ORDER BY a.account_id;
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", type=date.fromisoformat,
                        default=datetime.now(ZoneInfo("Asia/Seoul")).date())
    parser.add_argument("--time", type=time.fromisoformat, default=time(15))
    parser.add_argument("--password-env", default="DEMO_PASSWORD")
    parser.add_argument("--output", type=Path,
                        default=Path(__file__).with_name("demo-seed.generated.sql"))
    args = parser.parse_args()
    if args.date.isoweekday() == 7:
        parser.error("Choose a non-Sunday demo date, matching the original schedule rule")
    # T-90 and T-60 must avoid lunch, and T+30 must precede the 16:30 cutoff.
    if args.time not in (time(10, 30), time(11), time(11, 30), time(15), time(15, 30)):
        parser.error("--time must be 10:30, 11:00, 11:30, 15:00 or 15:30")
    password = os.environ.get(args.password_env, "Test1234!")
    if not 8 <= len(password) <= 72 or not password.strip():
        parser.error(f"Set {args.password_env} to an 8–72 character demo password")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Refuse overwrite; generated hashes are local artifacts, never source fixtures.
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(generate(args.date, args.time, password))
    print(f"Created {args.output}. No database was changed.")


if __name__ == "__main__":
    main()
