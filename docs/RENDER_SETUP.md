# Render 무료 시연 배포

복제된 팀 Notion의 본문·회의·API 대조는
[검토 결과](NOTION_REVIEW_2026-11-23.md)에 정리했다.
2026-11-23 배포용 브랜치 `suhhee/render-demo-deploy`를 GitHub에 올리고
온케어 전용 리소스를 생성했다. 실제 기동 검증 결과는 [배포 기록](RENDER_DEPLOYMENT_2026-11-23.md)에 기록한다.

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
- 배포 브랜치는 `suhhee/render-demo-deploy`이다. 공개 Git URL로 연결한 서비스는
  Git provider 연동 없이 커밋 푸시만으로 자동 배포되지 않으므로 대시보드에서 수동 배포한다.

## 배포 전 확인

1. 최신 Notion의 업무 규칙·API·테이블 정의와 현재 Flyway 마이그레이션을 대조한다.
2. 실제 Render 워크스페이스, 기존 리소스, 프런트 배포 origin을 확인한다.
3. Render CLI가 준비된 환경에서 `render blueprints validate render.yaml`을 실행한다.
   JSON Schema 검증과 실제 플랫폼 검증은 별개다.
4. Docker가 실행 중인 환경에서 `docker build -t oncare-be .`를 실행한다.
5. 검증된 변경을 저장소의 배포 브랜치에 반영한 뒤 Blueprint를 생성한다.
   로컬 파일만으로는 Render가 새 설정을 읽을 수 없다.
6. DB의 Flyway 적용, 헬스 체크, 로그인과 역할별 조회를 확인한다.
7. `render` 프로필과 `DEMO_SEED_ON_START=true`를 함께 설정하면 Flyway 완료 후
   `src/main/resources/demo/initial-data.sql`을 빈 DB에 한 번 적용한다.
   계정이 이미 있으면 기존 데이터를 유지하고 초기화를 건너뛴다.
   기존 `R__seed_medicine.sql`은 Flyway를 통해 약품·금기 기준을 삽입한다.
   별도 SQL 적용 절차는 [시연 데이터 안내](DEMO_DATA.md)를 참고한다.

프런트를 다른 호스팅에 유지할 경우 `VITE_API_BASE_URL`을
`https://<실제 BE 호스트>/api/v1`로 변경하고 프런트를 다시 빌드해야 한다.

## 무료 플랜과 시연 일정

2026-11-23 공식 문서 확인 기준:

- 무료 Web Service는 15분 유휴 상태 뒤 중지되며 재기동에 약 1분이 걸릴 수 있다.
- 무료 PostgreSQL은 1 GB이며 생성 후 30일에 만료된다. 무료 백업은 제공되지 않는다.
- 무료 Web Service는 SSH·대시보드 셸과 일회성 작업을 지원하지 않는다.

이번 DB의 만료일은 **2026-12-23 16:46 (한국 시간)**이다. 시연 데이터는 DB 재생성이 가능한
별도 SQL로 관리하고, 앱 재시작 때마다 업무 데이터를 지우는 방식은 피한다.

## 로컬 검증 결과 (2026-11-23)

- JDK 21의 `./gradlew --no-daemon bootJar` 성공.
- 공식 Render JSON Schema로 `render.yaml` 구조 검증 통과.
- JAR에 `application-render.properties` 포함, 테스트 프로필 미포함 확인.
- 기존 DB와 분리된 임시 PostgreSQL 18에 `render` 프로필로 기동 성공.
  `/actuator/health`는 `UP`, 성공한 Flyway 마이그레이션은 3개,
  약품은 194개, 사용자 계정은 0개였다. 검증용 프로세스는 종료했다.
- 준비 단계에서는 로컬 Docker 엔진과 Render CLI가 없어 이미지 빌드를 하지 않았다.
  이후 실제 Render에서 Docker 이미지 빌드와 기동, API·화면 검증까지 완료했다.
  결과는 [배포 기록](RENDER_DEPLOYMENT_2026-11-23.md)에 정리했다.

참고: [무료 플랜](https://render.com/docs/free),
[Docker](https://render.com/docs/docker),
[Blueprint 명세](https://render.com/docs/blueprint-spec),
[PostgreSQL 연결](https://render.com/docs/postgresql-creating-connecting)
