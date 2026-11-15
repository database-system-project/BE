# PostgreSQL 마이그레이션

확정된 ERD의 첫 DDL은 `V1__create_initial_schema.sql`에 추가합니다.
이후 스키마 변경은 다음 버전 SQL 파일로 추가하고, 팀원이 이미 적용한 마이그레이션은 수정하지 않습니다.

`V2__separate_doctor_from_staff.sql`은 기존 DOCTOR 계정의 중복 직원 프로필을 제거합니다.
의사 프로필과 예약·진료 연결은 그대로 유지됩니다. 접수·접수 취소·진찰료 설정·수납·환자 정보 정정의 수행자 참조는 기존 `*_by_staff_id`에서 `*_by_account_id`로 이전됩니다.
