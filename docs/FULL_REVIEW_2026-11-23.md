# 공공데이터·시드·API·ERD 후속 검토

검토일: 2026-11-23. 원격 develop과 로컬 기준 커밋은 `2d5b7fe`이며,
이 검토에서 수납 처방전의 약품명 응답을 로컬 수정했다. 실제 온케어·Render에는 배포하지 않았다.

## 1. 공공데이터와 약품명

현재 소스에는 공공 API를 실행 중에 호출하거나 주기적으로 동기화하는 클라이언트가 없다.
공공데이터를 미리 옮긴 `src/main/resources/db/migration/R__seed_medicine.sql`을
Flyway가 적용하고, 화면은 자체 DB API에서 조회하는 구조다.

시드 주석은 식약처 e약은요와 건강보험심사평가원 DUR 품목 리스트(2026.6)를 출처로 적었다.
`9a22ae7`은 약품·금기 시드를 추가한 커밋이다. 원천 다운로드 이력·원본 응답 파일은
저장소에서 확인되지 않아 각 행이 최신 원천과 일치하는지까지 증명하지는 못했다.
발급된 공공 API 키를 이용한 원천 재수집은 이번 검토에서 수행하지 않았다.

공식 [e약은요 API](https://www.data.go.kr/data/15075057/openapi.do)는 제품명·품목기준코드·효능·사용법 등을 제공하고,
[DUR 품목정보 API](https://www.data.go.kr/data/15059486/openapi.do)는 병용·특정연령대 금기 등을 제공한다.
프로젝트는 이들 중 선택한 약품을 사전 적재한 시연용 범위이며 전체 품목 검색 서비스가 아니다.

| 검증 대상 | 결과 |
| --- | --- |
| 로컬 Flyway 기준 약품 | 194건 |
| 이름이 NULL 또는 빈 문자열인 약품 | 0건 |
| DUR 전용 코드 약품 | 65건 |
| 효능이 채워진 약품 | 128건. 안내문 결측과 이름 누락은 별개 |
| 현재 온케어 서버 조회 | 약품 194건, 빈 이름 0건, DUR 전용 코드 65건 |

DUR 상대 약품 중 e약은요에 없는 제품도 `DUR` + 제품코드로 `medicine`에 등록돼 있으며,
제품명·업체명은 있고 e약은요 안내문은 NULL일 수 있다. 이는 테이블 정의서의 설명과도 일치한다.

약품명 추가 커밋 `ae92efd`는 담당 진료 상세·처방 금기 응답을 보완했다.
실제로 `currentMedications[].medicationName`, 처방 품목의 `medicationName`,
금기 경고의 `medicationNames`가 존재한다.
수납 처방전은 여전히 `prescription_item`만 조회해 이름을 빼먹고 있었다.
운영 서버의 결제된 처방전 조회 표본에서도 `medicationName`이 없었다.

이번 수정은 `BillingService.print`에서 medicine을 조인하고 기존
`Views.prescriptionItemWithName`을 사용해 `medicationName`을 반환하도록 했다.
기존 필드는 유지한다. FE는 이미 해당 필드를 표시하므로 새 응답을 바로 사용할 수 있다.
회귀 테스트는 활성 처방의 약품명이 실제로 반환되는지 확인하도록 보완했다.

## 2. local-test-seed.sql 기반 더미 데이터

FE 원본의 계정 6개, 비밀번호 `Test1234!`, 각 해시·이름·진료과·환자 정보·요금 시작일을 유지했다.
의사 staff 중복 행을 제거하고 `set_by_staff_id`를 `set_by_account_id`로 바꿨다.
FE 원본 파일도 V2에 맞게 수정했고, BE 안에도 독립 실행 가능한 기본 시드를 두었다.
두 파일을 같은 빈 검증 DB에 순서대로 적용해 계정 6개·staff 3명 상태가 유지됨을 확인했다.

- 기본정보만: `dev/local-test-seed.sql`
- 전체 흐름: `dev/generate_demo_seed.py`가 생성한 `dev/demo-seed.generated.sql`
- 두 종류 중 하나를 선택한다. 기본 시드 적용 후 전체 시나리오 SQL을 연속 적용하지 않는다.

전체 시나리오는 원본에 `patient02`·`patient03`을 더해 계정 8개, 예약 8건,
진료 5건, 처방 4건, 결제 2건, 금기 예외 2건을 준비한다.
원본의 오늘부터 14일·일요일 제외 일정 규칙을 유지하고, 과거 처방 이력용 직전 진료일만 추가한다.
환자·예약·진료·투약값·예외 사유는 가상이며, 약품과 금기 기준은 공공데이터 시드에서 참조한다.
현재 생성 파일은 2026-11-23 15:00 기준이다. 실제 시연 날짜로 다시 생성할 수 있다.

전체 시나리오 SQL은 기존 계정·진찰료가 있으면 중단한다. DELETE·TRUNCATE·시퀀스 초기화를 하지 않는다.
기존 온케어 DB에 자동 덮어쓰는 도구가 아니다. 사용법은 [시연 데이터 안내](DEMO_DATA.md)를 따른다.

## 3. 노션 요청·응답 대조

복제된 Notion의 [모든 엔드포인트](https://app.notion.com/p/3ce9385946128308a7cd0174554344dd)
69개 상세 문서를 조회하고, 임시 PostgreSQL 18에 실제 앱을 실행해 HTTP로 호출했다.
기계 판독 결과는 [검증 기록](api-contract-audit.json)에 있다.

| 검증 | 결과 |
| --- | --- |
| 메서드·경로 | 69개 일치 |
| 정상 요청·성공 상태 | 69개 호출 성공 |
| 명세에 선언된 응답 필드·자료형 | 68개에서 해당 표본의 필수 필드 확인. 직원 상세 1개 차이 |
| 보호 API 미인증 요청 | 67개 모두 401 |
| 구형 DOCTOR 직원 생성·필터·수정 요청 | 3개 변형 모두 400 INVALID_REQUEST. 노션과 차이 |
| 연령·병용 경고의 조건부 ruleKey | AGE는 medicationId/ageSeq, INTERACTION은 medicationAId/medicationBId 확인 |
| 기존 테스트·추가 회귀 검증 | Gradle build 성공, 테스트 156개 통과 |

### 확인된 문서 차이

1. 직원 계정 등록·목록 필터·수정 문서는 `DOCTOR`를 허용하지만,
   V2 이후 코드는 RECEPTION·CASHIER·ADMIN만 직원으로 관리한다.
   의사 계정은 의사 등록 경로에서 만들거나 기존 DOCTOR 계정에 연결한다.
2. 직원 상세 명세는 `doctorId: integer | null`을 요구하지만 실제 응답에는 이 키가 없다.
   의사·직원 분리 후 문서가 남은 것으로 판단하며, 기존 구조로 코드를 되돌리지 않았다.
3. 의사 등록 요청에는 코드가 지원하는 `loginId`, `initialPassword`, `accountActive`의
   계정 동시 생성 방식이 빠져 있다. 응답에는 `loginId`, `accountActive`가 추가로 온다.
4. 담당 진료 상세의 약품명·기록·처방 상세, 금기 경고의 `medicationNames`와
   병용 ruleKey 필드는 응답 필드 표에 충분히 풀어 적히지 않았다.
   AGE와 INTERACTION의 ruleKey는 서로 다른 조건부 구조로 문서화해야 한다.
5. 수납 처방전의 `medicationName`은 이번 코드 수정에 맞춰 응답 표에도 추가해야 한다.

여기서 정상 응답 검증은 각 API의 유효한 표본 요청과 선언된 응답 필드·자료형 대조다.
추가 필드는 별도로 기록했고, 빈 배열·null 부모 아래의 분기 일부는 표본으로 실행되지 않았다.
모든 선택 필터·역할 조합·길이 경계·업무 오류·동시성 경우를 전수 증명한 결과는 아니다.
따라서 요청·응답이 완전히 일치한다고 결론내리지 않는다.

## 4. 로컬 ERD·테이블 정의서·시스템 구성도

사용자가 지정한 `0 프로젝트/11테이블정의서.pdf`(물리 9쪽, 표 18개),
`12erd.png`, `11시스템구성도.pdf`를 읽고 주요 페이지를 렌더링해 확인했다.

테이블 정의서 18개 테이블·116개 컬럼과 실제 PostgreSQL 18개 테이블·116개 컬럼을 비교했다.
아래 5개 이름 변경을 매핑하면 컬럼 자료형과 NOT NULL 여부는 일치한다.
이 비교가 모든 FK·인덱스·CHECK 표현식까지 완전 일치함을 증명하는 것은 아니다.

| 테이블 | PDF·ERD 컬럼 | V2 이후 현재 컬럼·참조 |
| --- | --- | --- |
| visit | checked_in_by_staff_id | checked_in_by_account_id → user_account |
| visit | cancelled_by_staff_id | cancelled_by_account_id → user_account |
| consultation_fee | set_by_staff_id | set_by_account_id → user_account |
| payment | processed_by_staff_id | processed_by_account_id → user_account |
| patient_demographic_audit | changed_by_staff_id | changed_by_account_id → user_account |

의사와 staff 분리 자체는 PDF의 staff 설명에 이미 반영돼 있다.
수행자 FK의 컬럼명·연결선은 V2 이전 상태이므로 두 설계 산출물에 맞춰 반영해야 한다.
ERD의 DATETIME·TINYINT·DECIMAL은 MySQL Workbench 표시이며,
PDF가 PostgreSQL의 TIMESTAMPTZ·BOOLEAN·NUMERIC과 대응한다고 명시했다.
이 타입 표시 차이만으로 MySQL용 구현이라고 판단하지 않는다.

시스템 구성도는 5개 역할과 회원가입→예약→접수→진료→금기 확인→수납 흐름이 현재 기능과 대응한다.
로그아웃은 클라이언트의 세션 삭제이며 별도 서버 로그아웃 API가 없는 점도 API 정리본의 범위와 맞는다.
원본 PDF·PNG는 수정하지 않았고 문서 변경이 필요한 지점만 기록했다.

## 적용 범위

이번 변경은 로컬 코드·시드·검토 문서다. 공공데이터 원본을 덮어쓰거나 운영 데이터를 초기화하지 않았다.
Git 커밋·푸시, 온케어 재배포, Render 리소스 생성·DB 적용은 수행하지 않았다.

## API별 정상 응답 대조

아래 판정은 선언된 응답 필드의 해당 표본 검증 결과이며 위의 공통 범위 제한을 따른다.

| 메서드 | 경로 | HTTP | 선언 필드 |
| --- | --- | --- | --- |
| GET | `/api/v1/admin/age-contraindications` | 200 | 확인 |
| POST | `/api/v1/admin/age-contraindications` | 201 | 확인 |
| GET | `/api/v1/admin/age-contraindications/{medicationId}/{ageSeq}` | 200 | 확인 |
| PATCH | `/api/v1/admin/age-contraindications/{medicationId}/{ageSeq}` | 200 | 확인 |
| GET | `/api/v1/admin/consultation-fee` | 200 | 확인 |
| PATCH | `/api/v1/admin/consultation-fee` | 200 | 확인 |
| GET | `/api/v1/admin/departments` | 200 | 확인 |
| POST | `/api/v1/admin/departments` | 201 | 확인 |
| GET | `/api/v1/admin/departments/{departmentId}` | 200 | 확인 |
| PATCH | `/api/v1/admin/departments/{departmentId}` | 200 | 확인 |
| GET | `/api/v1/admin/doctors` | 200 | 확인 |
| POST | `/api/v1/admin/doctors` | 201 | 확인 |
| GET | `/api/v1/admin/doctors/{doctorId}` | 200 | 확인 |
| PATCH | `/api/v1/admin/doctors/{doctorId}` | 200 | 확인 |
| GET | `/api/v1/admin/drug-interactions` | 200 | 확인 |
| POST | `/api/v1/admin/drug-interactions` | 201 | 확인 |
| GET | `/api/v1/admin/drug-interactions/{medicationAId}/{medicationBId}` | 200 | 확인 |
| PATCH | `/api/v1/admin/drug-interactions/{medicationAId}/{medicationBId}` | 200 | 확인 |
| GET | `/api/v1/admin/medications` | 200 | 확인 |
| POST | `/api/v1/admin/medications` | 201 | 확인 |
| GET | `/api/v1/admin/medications/{medicationId}` | 200 | 확인 |
| PATCH | `/api/v1/admin/medications/{medicationId}` | 200 | 확인 |
| GET | `/api/v1/admin/schedules` | 200 | 확인 |
| POST | `/api/v1/admin/schedules` | 201 | 확인 |
| DELETE | `/api/v1/admin/schedules/{scheduleId}` | 204 | 확인 |
| GET | `/api/v1/admin/schedules/{scheduleId}` | 200 | 확인 |
| PATCH | `/api/v1/admin/schedules/{scheduleId}` | 200 | 확인 |
| GET | `/api/v1/admin/staff-accounts` | 200 | 확인 |
| POST | `/api/v1/admin/staff-accounts` | 201 | 확인 |
| GET | `/api/v1/admin/staff-accounts/{accountId}` | 200 | 차이: doctorId 미포함 |
| PATCH | `/api/v1/admin/staff-accounts/{accountId}` | 200 | 확인 |
| GET | `/api/v1/appointments` | 200 | 확인 |
| POST | `/api/v1/appointments` | 201 | 확인 |
| GET | `/api/v1/appointments/{appointmentId}` | 200 | 확인 |
| POST | `/api/v1/appointments/{appointmentId}/cancel` | 200 | 확인 |
| POST | `/api/v1/appointments/{appointmentId}/reschedule` | 200 | 확인 |
| POST | `/api/v1/auth/login` | 200 | 확인 |
| POST | `/api/v1/auth/signup` | 201 | 확인 |
| GET | `/api/v1/billing/visits` | 200 | 확인 |
| GET | `/api/v1/billing/visits/{visitId}` | 200 | 확인 |
| POST | `/api/v1/billing/visits/{visitId}/payments` | 200 | 확인 |
| GET | `/api/v1/billing/visits/{visitId}/prescription-print` | 200 | 확인 |
| GET | `/api/v1/departments` | 200 | 확인 |
| GET | `/api/v1/departments/{departmentId}/doctors` | 200 | 확인 |
| GET | `/api/v1/doctor/medications` | 200 | 확인 |
| POST | `/api/v1/doctor/prescriptions/{prescriptionId}/cancel` | 200 | 확인 |
| GET | `/api/v1/doctor/schedules` | 200 | 확인 |
| GET | `/api/v1/doctor/visits/{visitId}` | 200 | 확인 |
| POST | `/api/v1/doctor/visits/{visitId}/complete` | 200 | 확인 |
| PATCH | `/api/v1/doctor/visits/{visitId}/medical-record` | 200 | 확인 |
| POST | `/api/v1/doctor/visits/{visitId}/medical-record` | 201 | 확인 |
| POST | `/api/v1/doctor/visits/{visitId}/prescription-checks` | 200 | 확인 |
| POST | `/api/v1/doctor/visits/{visitId}/prescriptions` | 201 | 확인 |
| POST | `/api/v1/doctor/visits/{visitId}/start` | 200 | 확인 |
| GET | `/api/v1/doctors/{doctorId}/availability` | 200 | 확인 |
| GET | `/api/v1/me` | 200 | 확인 |
| PATCH | `/api/v1/me/password` | 200 | 확인 |
| GET | `/api/v1/me/prescriptions/{prescriptionId}` | 200 | 확인 |
| PATCH | `/api/v1/me/profile` | 200 | 확인 |
| GET | `/api/v1/me/visits` | 200 | 확인 |
| GET | `/api/v1/me/visits/{visitId}` | 200 | 확인 |
| GET | `/api/v1/payments/{paymentId}/receipt` | 200 | 확인 |
| GET | `/api/v1/reception/appointments` | 200 | 확인 |
| POST | `/api/v1/reception/appointments/{appointmentId}/check-in` | 201 | 확인 |
| POST | `/api/v1/reception/visits/{visitId}/cancel` | 200 | 확인 |
| GET | `/api/v1/staff/patients` | 200 | 확인 |
| GET | `/api/v1/staff/patients/{patientId}` | 200 | 확인 |
| PATCH | `/api/v1/staff/patients/{patientId}/demographics` | 200 | 확인 |
| GET | `/api/v1/visits/queue` | 200 | 확인 |
