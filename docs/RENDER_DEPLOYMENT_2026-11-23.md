# Render 배포 기록 — 2026-11-23

## 접속

- 사용자 화면: https://oncare-demo.onrender.com
- 백엔드: https://oncare-be.onrender.com
- 헬스: https://oncare-be.onrender.com/actuator/health
- Blueprint: https://dashboard.render.com/blueprint/exs-dc1uulqjnfac739sd4og
- BE 대시보드: https://dashboard.render.com/web/srv-dc1uv4nf3r2c73drnpig
- FE 대시보드: https://dashboard.render.com/static/srv-dc1uv9uq1p3s73ba004g

기존 yj-dev.duckdns.org 서버와 별개인 새 Render 환경이다.

## 배포된 버전과 상태

- BE: `6fe91ab8c3841c82c3f081c9afb2e3c30d73b9d3`
  - deploy `dep-dc1uv5ff3r2c73drnr70`: live, 2026-11-23 16:51 한국 시간.
- FE: `1905d77392c7eba76f5479225eb53d41103eb572`
  - deploy `dep-dc1uva6q1p3s73ba00t0`: live, 2026-11-23 16:48 한국 시간.
- 두 저장소의 배포 브랜치: `suhhee/render-demo-deploy`.
- 검토용 PR: https://github.com/database-system-project/BE/pull/44,
  https://github.com/database-system-project/FE/pull/16.
- 팀 기본 브랜치는 합치지 않았다. 배포는 위 브랜치를 직접 참조한다.

## DB와 SQL 반영

- 싱가포르 무료 PostgreSQL 18: `oncare-postgres`.
- 외부 접속을 차단한 내부 네트워크로만 BE와 연결한다.
- Flyway로 스키마 및 약품·금기 기준 데이터를 적용했다.
- `render` 프로필 + `DEMO_SEED_ON_START=true`에서
  `src/main/resources/demo/initial-data.sql`을 한 트랜잭션으로 적용했다.
- 2026-11-23 16:51:06 한국 시간에 `Initialized fresh demonstration database` 로그 확인.
- 시연 기준일은 **2026-11-23 15:00 한국 시간**이다. 날짜를 자동으로 매일 갱신하지 않는다.
  날짜가 바뀌면 조회 기간을 해당 날짜로 지정한다. 새 시연일을 준비할 때는
  생성기로 SQL을 다시 만들되 기존 DB에는 자동 덧씌우지 않는다.
- 기존 계정이 있으면 재배포 때 시드를 건너뛰어 기존 데이터를 보존한다.
- DB는 **2026-12-23 16:46 한국 시간**에 만료된다.

## 검증 결과

- 로컬 PostgreSQL 18 기반 전체 Gradle build: 157개 테스트, 실패·오류·건너뜀 0.
- 프런트엔드 프로덕션 빌드 성공. Render에서도 두 서비스 빌드 성공.
- 실제 `/actuator/health`: `UP`.
- 시연 계정 8개 모두 로그인 성공: admin01, desk01, cashier01,
  doctor01, doctor02, patient01, patient02, patient03.
- 공통 공개 테스트 비밀번호: `Test1234!`.
- 관리자 약품·진료과 조회, 환자 본인 조회, 수납 완료 진료 조회: HTTP 200.
- 수납 완료된 과거 진료 2건의 처방전 출력 API: HTTP 200,
  `medicationName` 포함, `canPrint=true`.
- CORS preflight: HTTP 200, Allow-Origin `https://oncare-demo.onrender.com`.
- 브라우저 관리자 로그인 및 대시보드 확인: 진료과 2, 의사 2,
  약품 194, 병용금기 136, 연령금기 52.
- 브라우저 환자 로그인 성공. 다음 예약(11월 24일), 최근 진료·처방·영수증 표시 확인.
- 외부 DB SQL 조회는 네트워크 차단 정책 때문에 실행하지 않았다.
  적용 여부는 서버 초기화 완료 로그와 실제 API·브라우저 응답으로 검증했다.

## 이후 반영 방법

공개 Git URL 방식은 Git provider 연결이 없어 push 후 자동 배포되지 않는다.
각 Render 서비스의 Manual Deploy → Deploy latest commit을 사용한다.
FE 설정:

- Build: `cd system-prototype && corepack pnpm install --frozen-lockfile && corepack pnpm build`
- Publish: `system-prototype/dist`
- `NODE_VERSION=22`, `SKIP_INSTALL_DEPS=true`
- `VITE_API_BASE_URL=https://oncare-be.onrender.com/api/v1`

BE의 `CORS_ALLOWED_ORIGINS`는 sync:false 값이며 실제 FE origin을 포함한다.
주소 변경 시 Render 환경변수를 수정하고 새 설정을 적용하는 배포를 실행한다.
기존 DB에 추가 SQL을 반영할 때는 검토한 Flyway 마이그레이션으로 관리한다.

GitHub CI는 빌드·테스트 이후 테스트 결과 파일 업로드 단계에서 조직의
Artifact storage quota 한도로 실패했다. 테스트 실패와 구분해야 하며,
추가 CI 실행 전 조직 저장공간 정리가 필요하다.

## 17시 이후 시연 허용 배포

- BE `8337b9abbd1c2b289f0282183dd2af8fa8b6c566`, FE `eafad6224d334e58c0c258227c2035dc2931814b` 배포 완료.
- BE deploy `dep-dc1vnqvf3r2c73dtni1g`: live, 2026-11-23 17:43 한국 시간.
- FE deploy `dep-dc1vnuec6qcc73c0i020`: live, 2026-11-23 17:40 한국 시간.
- Render BE 환경변수 `DEMO_RELAXED_HOURS=true` 활성화.
- 전체 테스트 160개 통과. 기본 모드의 시간 제한 유지 및 18:30 기준 시연 예약·접수 통합 테스트 포함.
- 실제 서버에서 2026-11-23 17:44에 당일 슬롯 13개 조회, 당일 지난 시간 예약 201,
  예정 예약 목록 표시, 마감 후 접수 201, 중복 접수 차단 409 검증.
- 검증용 예약 17 / 진료 7은 검증 후 원무 권한으로 취소(200)하여 대기열에 남기지 않았다.
- 시각은 실제 한국 시간을 유지하며, 시연 옵션은 당일의 경과 시간과 마감 제한만 완화한다.
- 기존 계정과 DB는 보존했다. 지난 날짜·휴진·점심시간·점유된 슬롯 제한은 유지한다.
