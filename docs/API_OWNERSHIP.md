# 담당 API 개발 계획

JDK 21 · Spring Boot 3.5 · PostgreSQL 18 기준. 빨간색 담당 7개 도메인의 API 34개를 구현합니다. 관리 API는 이 계획의 범위가 아니며 공통 스키마는 담당 팀원과 공유합니다.

## 개발·검토 순서

| 순서 | 개발 단위 | 이슈 | 브랜치 | API 수 |
| --- | --- | --- | --- | --- |
| 1 | 인증 및 공통 API 기반 | [#8](https://github.com/database-system-project/BE/issues/8) | feat/#8 | 2 |
| 2 | 환자 정보 관련 API | [#9](https://github.com/database-system-project/BE/issues/9) | feat/#9 | 6 |
| 3 | 예약 관련 API | [#10](https://github.com/database-system-project/BE/issues/10) | feat/#10 | 5 |
| 4 | 접수·진료 관련 API | [#11](https://github.com/database-system-project/BE/issues/11) | feat/#11 | 9 |
| 5 | 처방·금기 관련 API | [#12](https://github.com/database-system-project/BE/issues/12) | feat/#12 | 4 |
| 6 | 수납·영수증 관련 API | [#13](https://github.com/database-system-project/BE/issues/13) | feat/#13 | 5 |
| 7 | 환자 이용내역 관련 API | [#14](https://github.com/database-system-project/BE/issues/14) | feat/#14 | 3 |

공통 기반을 먼저 검토·병합합니다. 후속 기능 브랜치는 develop에서 만든 뒤 같은 공통 기반 커밋을 상속합니다. 모든 PR의 대상은 팀 규칙대로 develop이며, 공통 기반 병합 전에는 같은 기반 변경이 함께 표시됩니다. 기능 간에는 공유 스키마와 공통 조회·권한 도구로 연동하고 다른 기능의 컨트롤러·서비스에 의존하지 않습니다.

## API 계약

각 상세 페이지가 요청·응답·권한·업무 오류의 기준입니다.

| 도메인 | API | 메서드 | 경로 |
| --- | --- | --- | --- |
| 인증 | [환자 회원가입](https://app.notion.com/p/66093859461282a8ae11018287e7efd6) | POST | /api/v1/auth/signup |
| 인증 | [로그인](https://app.notion.com/p/607938594612828b883401966128f1aa) | POST | /api/v1/auth/login |
| 환자 정보 | [내 정보 조회](https://app.notion.com/p/86e93859461282bf9a92818e4073f45d) | GET | /api/v1/me |
| 예약 | [내 예약 목록](https://app.notion.com/p/4149385946128164916fcf2d833cc698) | GET | /api/v1/appointments |
| 예약 | [내 예약 상세](https://app.notion.com/p/41493859461281359252c7a525f23dc1) | GET | /api/v1/appointments/{appointmentId} |
| 예약 | [내 예약 취소](https://app.notion.com/p/4149385946128175883df1670b25962a) | POST | /api/v1/appointments/{appointmentId}/cancel |
| 예약 | [예약 시간 변경](https://app.notion.com/p/41493859461281efa023fdcf7c3703c0) | POST | /api/v1/appointments/{appointmentId}/reschedule |
| 예약 | [예약 신청](https://app.notion.com/p/41493859461281b1bc51cfe475688b4e) | POST | /api/v1/appointments |
| 수납·영수증 | [수납 대기·결제 완료 목록](https://app.notion.com/p/4149385946128114941bf9e31148e3f2) | GET | /api/v1/billing/visits |
| 수납·영수증 | [전자영수증 조회](https://app.notion.com/p/41493859461281408785e7de2d83958a) | GET | /api/v1/payments/{paymentId}/receipt |
| 수납·영수증 | [결제 확인용 상세 조회](https://app.notion.com/p/414938594612815ab029fcc747bc04cf) | GET | /api/v1/billing/visits/{visitId} |
| 수납·영수증 | [처방전 발급용 조회](https://app.notion.com/p/414938594612818587b8eae7212d5673) | GET | /api/v1/billing/visits/{visitId}/prescription-print |
| 수납·영수증 | [진찰료 결제 완료 처리](https://app.notion.com/p/41493859461281c2aa9cd011aac1fbad) | POST | /api/v1/billing/visits/{visitId}/payments |
| 환자 이용내역 | [내 진료기록 상세](https://app.notion.com/p/4149385946128119b122dd708a8c51e2) | GET | /api/v1/me/visits/{visitId} |
| 처방·금기 | [처방 저장 및 금기 예외 기록](https://app.notion.com/p/414938594612812e98a5c022ee8eaf6b) | POST | /api/v1/doctor/visits/{visitId}/prescriptions |
| 처방·금기 | [처방 금기 사전 확인](https://app.notion.com/p/414938594612815aa01bf4882251d1fc) | POST | /api/v1/doctor/visits/{visitId}/prescription-checks |
| 환자 이용내역 | [내 진료 이용내역 목록](https://app.notion.com/p/414938594612819f9538e6d589ced587) | GET | /api/v1/me/visits |
| 환자 이용내역 | [내 처방·복약 안내](https://app.notion.com/p/41493859461281a3bb2de5d95d4c36e1) | GET | /api/v1/me/prescriptions/{prescriptionId} |
| 처방·금기 | [처방 취소](https://app.notion.com/p/41493859461281a4a2aad6ff75cce7df) | POST | /api/v1/doctor/prescriptions/{prescriptionId}/cancel |
| 처방·금기 | [처방용 활성 약품 검색](https://app.notion.com/p/4149385946128108911dc586fd42d17c) | GET | /api/v1/doctor/medications |
| 접수·진료 | [진료기록 수정](https://app.notion.com/p/4149385946128119b6b1f3846dbd9d4b) | PATCH | /api/v1/doctor/visits/{visitId}/medical-record |
| 접수·진료 | [담당 진료 상세](https://app.notion.com/p/41493859461281228191ccf6f5bebe0a) | GET | /api/v1/doctor/visits/{visitId} |
| 접수·진료 | [진료 시작](https://app.notion.com/p/414938594612812aba43c3d5f5d75d12) | POST | /api/v1/doctor/visits/{visitId}/start |
| 접수·진료 | [진료 완료](https://app.notion.com/p/414938594612815bbb12c28c68d3948a) | POST | /api/v1/doctor/visits/{visitId}/complete |
| 접수·진료 | [진료기록 작성](https://app.notion.com/p/41493859461281dabad8dc600ff8fb50) | POST | /api/v1/doctor/visits/{visitId}/medical-record |
| 접수·진료 | [예약 환자 도착 접수](https://app.notion.com/p/414938594612815cb96fe60584d2d034) | POST | /api/v1/reception/appointments/{appointmentId}/check-in |
| 접수·진료 | [접수 취소](https://app.notion.com/p/414938594612817db6c6c54319692d57) | POST | /api/v1/reception/visits/{visitId}/cancel |
| 접수·진료 | [진료 대기열 조회](https://app.notion.com/p/41493859461281e39b97de22423d6c7d) | GET | /api/v1/visits/queue |
| 접수·진료 | [당일 예약·접수 검색](https://app.notion.com/p/41493859461281ebb19cd87075408cd5) | GET | /api/v1/reception/appointments |
| 환자 정보 | [내 기본정보 수정](https://app.notion.com/p/41493859461281528342f150d38ea70e) | PATCH | /api/v1/me/profile |
| 환자 정보 | [정정 대상 환자 조회](https://app.notion.com/p/4149385946128165ab66c473085ff8e5) | GET | /api/v1/staff/patients/{patientId} |
| 환자 정보 | [환자 생년월일·성별 정정](https://app.notion.com/p/414938594612818e9457ebbdd8ac23fd) | PATCH | /api/v1/staff/patients/{patientId}/demographics |
| 환자 정보 | [환자 검색](https://app.notion.com/p/41493859461281adb32be92143fa3f71) | GET | /api/v1/staff/patients |
| 환자 정보 | [내 비밀번호 변경](https://app.notion.com/p/41493859461281d49acedad1a27cae67) | PATCH | /api/v1/me/password |

## 검증 환경

통합 테스트는 실제 PostgreSQL에서 Flyway를 실행합니다. TEST_DB_URL·TEST_DB_USERNAME·TEST_DB_PASSWORD로 테스트 전용 DB를 지정합니다. 테스트는 업무 테이블을 초기화하므로 운영·개인 개발 데이터가 있는 DB를 지정하지 않습니다.

## 공통 저장 규칙

- 사용자 역할은 PATIENT·RECEPTION·DOCTOR·CASHIER·ADMIN입니다.
- 순간 값은 TIMESTAMPTZ, 업무 날짜는 Asia/Seoul로 계산합니다.
- 예약 중복은 점유 상태 부분 유일 인덱스, 접수·기록·결제 중복은 DB 유일 제약으로 차단합니다.
- 환자 정정 감사 기록·접수 취소 사유·토큰 무효화 버전은 공통 스키마에 포함합니다.
- 운영 기준 데이터와 직원 계정의 실제 초기 비밀번호는 소스에 넣지 않습니다.
