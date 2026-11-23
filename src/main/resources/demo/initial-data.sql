-- GENERATED: synthetic hospital demo, current PostgreSQL V2 schema.
-- Apply only AFTER Flyway V1, V2, R__seed_medicine.
-- psql -X -v ON_ERROR_STOP=1 -f <this file>
-- No DELETE/TRUNCATE/reset; refuses a database with any existing accounts.
BEGIN;
SET LOCAL standard_conforming_strings = on;
SET LOCAL TIME ZONE 'Asia/Seoul';
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
SELECT set_config('oncare.demo_day', '2026-11-23', true);
SELECT set_config('oncare.demo_start', '15:00', true);

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
    ('admin01', '897295bed1da42b188c50998e74cde997a73581c7f406014778e17393bb4b37d5e7955ff3b616d458ba987947fa061b5', 'ADMIN'),
    ('desk01', 'eacc4fe8351657e4970a1c7ad40ff86edccc3fc58f583d1a9fac46241faad731a7f7accea2232df76d4acfcd0e1d0d98', 'RECEPTION'),
    ('cashier01', 'd5dea254962ed018b7f58953f79233f7c4fc76555878b316200e968118d039b1a87adb18cab4c3d64d9128cab56c3f07', 'CASHIER'),
    ('doctor01', 'd859f74df07d4cfd96d367869a3fd6fc67c00f7d86e86a3dae2a3808a81b7b28a4e9f0380e39d76662adf87e476a1e65', 'DOCTOR'),
    ('doctor02', 'b5d3e642039911b05b04af3bcff3caae67505ad7c8c25cf9343b95c20638566064c2a246e004817b9def2edda3134ae7', 'DOCTOR'),
    ('patient01', 'b104ce9653008351e2a3f30a2365348d127513841ef7e94ee67ec4e022d33743c5901e74fd60a080e26b3d2fd2842f9a', 'PATIENT'),
    ('patient02', '1b1456dd2093b8a4475096a63ac8a948e24e0c19d1a408bff1edf402f301b7438fb72bff0c63bfdc152b057f8e1a24ad', 'PATIENT'),
    ('patient03', '1dedb4bd889aa2f3a60231df66cf033a6eedfd85a3f43e7d46776dcc2c0c2d379791b12eb5337ee7bbbcb5602df34df8', 'PATIENT');
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
