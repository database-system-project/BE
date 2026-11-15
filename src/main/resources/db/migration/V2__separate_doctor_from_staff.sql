-- 기존 직원 역할에서 수행한 작업도 계정으로 추적할 수 있도록 참조를 옮긴다.
ALTER TABLE visit
    ADD COLUMN checked_in_by_account_id BIGINT REFERENCES user_account,
    ADD COLUMN cancelled_by_account_id BIGINT REFERENCES user_account;

UPDATE visit v SET checked_in_by_account_id = s.account_id
  FROM staff s WHERE s.staff_id = v.checked_in_by_staff_id;
UPDATE visit v SET cancelled_by_account_id = s.account_id
  FROM staff s WHERE s.staff_id = v.cancelled_by_staff_id;

ALTER TABLE visit
    ALTER COLUMN checked_in_by_account_id SET NOT NULL,
    DROP COLUMN checked_in_by_staff_id,
    DROP COLUMN cancelled_by_staff_id;

ALTER TABLE consultation_fee ADD COLUMN set_by_account_id BIGINT REFERENCES user_account;
UPDATE consultation_fee f SET set_by_account_id = s.account_id
  FROM staff s WHERE s.staff_id = f.set_by_staff_id;
ALTER TABLE consultation_fee
    ALTER COLUMN set_by_account_id SET NOT NULL,
    DROP COLUMN set_by_staff_id;

ALTER TABLE payment ADD COLUMN processed_by_account_id BIGINT REFERENCES user_account;
UPDATE payment p SET processed_by_account_id = s.account_id
  FROM staff s WHERE s.staff_id = p.processed_by_staff_id;
ALTER TABLE payment
    ALTER COLUMN processed_by_account_id SET NOT NULL,
    DROP COLUMN processed_by_staff_id;

ALTER TABLE patient_demographic_audit ADD COLUMN changed_by_account_id BIGINT REFERENCES user_account;
UPDATE patient_demographic_audit audit SET changed_by_account_id = s.account_id
  FROM staff s WHERE s.staff_id = audit.changed_by_staff_id;
ALTER TABLE patient_demographic_audit
    ALTER COLUMN changed_by_account_id SET NOT NULL,
    DROP COLUMN changed_by_staff_id;

-- 기존 로그인 차단 상태는 계정에 보존한다. 의사 이름은 doctor를 사용한다.
UPDATE user_account a SET account_status = 'INACTIVE'
  FROM staff s WHERE s.account_id = a.account_id AND a.role = 'DOCTOR' AND NOT s.staff_active;

DELETE FROM staff s USING user_account a WHERE s.account_id = a.account_id AND a.role = 'DOCTOR';
