# Render 무료 배포 준비

현재 설정은 배포 준비안이다. 복제된 팀 Notion의 본문·회의·API 대조는
[검토 결과](NOTION_REVIEW_2026-11-23.md)에 정리했다. 첨부 ERD·테이블 정의서의
내부 검토와 실제 Render 서비스·DB 생성, Git 원격 업로드는 아직 수행하지 않았다.

## 구성

- BE 저장소의 `Dockerfile`로 Java 21 실행 JAR를 빌드한다.
- `render.yaml`은 싱가포르의 무료 Web Service와 PostgreSQL 18을 함께 정의한다.
  기존 관련 Render 서비스가 있다면 생성 전에 같은 리전인지 확인한다.
- `render` 프로필은 `0.0.0.0`과 Render의 `PORT`에 바인딩한다.
- DB는 같은 리전의 내부 네트워크로 연결한다. `DB_HOST`, `DB_PORT`, `DB_NAME`,
  `DB_USERNAME`, `DB_PASSWORD`는 Blueprint의 DB 참조로 주입한다.
  Render의 `postgresql://...` URL을 JDBC URL에 그대로 넣지 않는다.
- DB 외부 접속은 `ipAllowList: []`로 차단한다. 외부에서 시드를 넣어야 할 경우
  실행 위치와 허용 IP를 먼저 정하고 필요한 범위만 일시적으로 허용한다.
- `JWT_SECRET`은 Render가 생성한다. `CORS_ALLOWED_ORIGINS`에는 실제 프런트의
  HTTPS origin을 입력한다. URL 경로나 끝의 `/`는 넣지 않는다.
- 헬스 체크는 인증 없이 제공되는 `/actuator/health`를 사용한다.
- 배포 브랜치는 `develop`이며 CI 통과 후 자동 배포하도록 설정했다.

## 배포 전 확인

1. 최신 Notion의 업무 규칙·API·테이블 정의와 현재 Flyway 마이그레이션을 대조한다.
2. 실제 Render 워크스페이스, 기존 리소스, 프런트 배포 origin을 확인한다.
3. Render CLI가 준비된 환경에서 `render blueprints validate render.yaml`을 실행한다.
   JSON Schema 검증과 실제 플랫폼 검증은 별개다.
4. Docker가 실행 중인 환경에서 `docker build -t oncare-be .`를 실행한다.
5. 검증된 변경을 저장소의 배포 브랜치에 반영한 뒤 Blueprint를 생성한다.
   로컬 파일만으로는 Render가 새 설정을 읽을 수 없다.
6. DB의 Flyway 적용, 헬스 체크, 로그인과 역할별 조회를 확인한다.
7. [시연 데이터 안내](DEMO_DATA.md)의 생성기로 시연 SQL을 준비하고 별도 명령으로 적용한다.
   앱 시작 시 가상 환자·예약·진료·수납 데이터를 자동 생성하지 않는다.
   기존 `R__seed_medicine.sql`은 Flyway를 통해 약품·금기 기준을 삽입한다.

프런트를 다른 호스팅에 유지할 경우 `VITE_API_BASE_URL`을
`https://<실제 BE 호스트>/api/v1`로 변경하고 프런트를 다시 빌드해야 한다.

## 무료 플랜과 시연 일정

2026-11-23 공식 문서 확인 기준:

- 무료 Web Service는 15분 유휴 상태 뒤 중지되며 재기동에 약 1분이 걸릴 수 있다.
- 무료 PostgreSQL은 1 GB이며 생성 후 30일에 만료된다. 무료 백업은 제공되지 않는다.
- 무료 Web Service는 SSH·대시보드 셸과 일회성 작업을 지원하지 않는다.

DB 생성일은 시연 일정에 맞춰 정한다. 시연 데이터는 DB 재생성이 가능한
별도 SQL로 관리하고, 앱 재시작 때마다 업무 데이터를 지우는 방식은 피한다.

## 로컬 검증 결과 (2026-11-23)

- JDK 21의 `./gradlew --no-daemon bootJar` 성공.
- 공식 Render JSON Schema로 `render.yaml` 구조 검증 통과.
- JAR에 `application-render.properties` 포함, 테스트 프로필 미포함 확인.
- 기존 DB와 분리된 임시 PostgreSQL 18에 `render` 프로필로 기동 성공.
  `/actuator/health`는 `UP`, 성공한 Flyway 마이그레이션은 3개,
  약품은 194개, 사용자 계정은 0개였다. 검증용 프로세스는 종료했다.
- 로컬 Docker 엔진과 Render CLI가 준비되지 않아 Docker 이미지 빌드 및
  Render 플랫폼 검증은 수행하지 않았다. 위 결과는 실제 Render 배포 성공을
  의미하지 않는다.

참고: [무료 플랜](https://render.com/docs/free),
[Docker](https://render.com/docs/docker),
[Blueprint 명세](https://render.com/docs/blueprint-spec),
[PostgreSQL 연결](https://render.com/docs/postgresql-creating-connecting)
