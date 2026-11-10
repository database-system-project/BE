# 🗣️ Database System BE

## 백엔드 개발 환경

JDK 21, Spring Boot 3.5, Gradle Wrapper 9.8.2, PostgreSQL 18을 사용합니다. JPA는 매핑한 엔티티의 스키마를 검증하고, DDL 변경은 Flyway SQL로 관리합니다. PostgreSQL 잠금·조회는 공통 JDBC 저장소를 통해 실행합니다.

### 로컬 실행

JDK 21과 Docker Compose를 준비합니다. 저장소 루트에 `.env`가 없다면 최초 한 번 아래 명령으로 생성합니다. 기존 `.env`와 DB 볼륨을 사용 중이라면 그대로 사용합니다.

```sh
(
    set -euC
    umask 077
    postgres_password="$(openssl rand -hex 24)"
    jwt_secret="$(openssl rand -hex 32)"
    cat > .env <<EOF
POSTGRES_DB=hospital
POSTGRES_USER=hospital
POSTGRES_PASSWORD=$postgres_password
POSTGRES_PORT=15432
JWT_SECRET=$jwt_secret
EOF
)
```

Docker Desktop을 실행하고 엔진이 준비되면 PostgreSQL과 Spring Boot를 시작합니다.

```sh
docker compose up -d --wait &&
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 헬스 체크는 `http://127.0.0.1:8080/actuator/health`입니다.
- Swagger UI는 로컬 프로필에서 `http://127.0.0.1:8080/swagger-ui.html`로 열 수 있습니다.
- PostgreSQL 포트는 기본 `15432`이고 `.env`의 `POSTGRES_PORT`로 변경할 수 있습니다.
- DB 연결에 사용하는 `.env` 값은 따옴표 없이 작성합니다. 볼륨을 처음 생성할 때 PostgreSQL 계정과 DB 이름이 초기화됩니다. `docker compose down`은 데이터를 보존합니다.
- Windows에서는 같은 키로 `.env`를 직접 만들고 비밀번호를 지정합니다. Gradle은 `.\gradlew.bat`으로 실행합니다.
- `local` 프로필 없이 실행할 때는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 환경 변수로 전달합니다.
- API 실행에는 32바이트 이상의 `JWT_SECRET`이 필요합니다. 기존 `.env`를 사용한다면 새 키만 별도로 추가하고 파일은 커밋하지 않습니다. `JWT_TTL_SECONDS` 기본값은 1800초입니다.

### 빌드 및 테스트

기본 DB 계정 `hospital`로 PostgreSQL을 실행한 후 테스트 전용 DB를 한 번 생성합니다. 테스트 실행 시 `.env`에 지정한 비밀번호를 전달합니다.

```sh
docker compose exec -T postgres createdb -U hospital hospital_test
TEST_DB_PASSWORD="$(sed -n 's/^POSTGRES_PASSWORD=//p' .env)" ./gradlew build
```

DB 연결 설정을 바꾼 경우 `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`를 테스트 DB에 맞게 지정합니다. 실행 JAR은 `build/libs/`에 생성됩니다.

`main`과 `develop`으로 향하는 PR 및 두 브랜치의 push에서 GitHub Actions가 PostgreSQL 서비스와 함께 빌드와 통합 테스트를 실행합니다. 실행 결과에서 테스트 리포트와 빌드에 성공한 JAR을 내려받을 수 있습니다.

### 기능 개발 시작점

- `V1__create_initial_schema.sql`은 업무 테이블·부분 유일 인덱스·감사 기록을 정의합니다. 이미 적용한 마이그레이션을 수정하지 말고 다음 버전의 SQL을 추가합니다.
- 회원가입·로그인과 헬스 체크, 로컬 Swagger 조회만 공개합니다. 나머지 API는 서명·만료·계정 활성·토큰 버전을 검증합니다.
- 비밀번호는 PBKDF2로 저장합니다. 계정 비활성화와 토큰 버전 변경은 다음 요청부터 적용합니다.
- 공통 DB·인증 기반과 기능별 개발 순서는 [담당 API 개발 계획](docs/API_OWNERSHIP.md)에 정리합니다.

## 🚀 Git Flow
- `main`
    - 프로젝트 최종 merge
    - 기본 프로젝트 세팅, 배포 가능한 브랜치, 항상 배포 가능한 상태를 유지
- `develop`
    - 개발 완료 전까지 완성한 기능들을 계속해서 merge
    - 배포 가능한 브랜치, 항상 배포 가능한 상태를 유지
- `{type}/{issue number}`
    - 개발 브랜치
    - 예: `feat/#5`, `fix/#11`

> 작업 단위로 이슈 생성 → 브랜치 생성 → 생성한 브랜치에서 작업 후 끝나면 develop 브랜치로 PR 남기기
>
> 모든 작업 시작 전 생성한 브랜치에서 develop 브랜치 pull을 받은 후 작업

&nbsp;
## 💡 PR Rules
- Assignee에는 본인을 지정해 주세요.
- Reviewers에는 본인을 제외한 백엔드 팀원을 지정한 후, 공유해 주세요.
- 이후, 팀원이 PR을 확인하고 승인해서 머지해 주세요.
  (해당 브랜치는 머지 후 자동 삭제되며, 복구도 가능합니다.)

&nbsp;
## 💻 Commit Message Convention
| **Type** | **Description** |
| --- | --- |
| **Feat** | 새로운 기능 추가 |
| **Fix** | 버그 수정 |
| **Docs** | 문서 수정 |
| **Style** | 코드 formatting, 세미콜론 누락, 코드 자체의 변경이 없는 경우 |
| **Refactor** | 코드 리팩토링 |
| **Test** | 테스트 코드, 리팩토링 테스트 코드 추가 |
| **Chore** | 패키지 매니저 수정, 그 외 기타 수정 (예: .gitignore) |
| **Design** | CSS 등 사용자 UI 디자인 변경 |
| **Comment** | 필요한 주석 추가 및 변경 |
| **Rename** | 파일 또는 폴더 명을 수정하거나 옮기는 작업만인 경우 |
| **Remove** | 파일을 삭제하는 작업만 수행한 경우 |
| **Init** | 프로젝트 초기 세팅 |
| **Merge** | 브랜치 merge |
| **!BREAKING CHANGE** | 커다란 API 변경의 경우 |
| **!HOTFIX** | 급하게 치명적인 버그를 고쳐야 하는 경우 |
> Type: commit title
>
> ex. `Feat: 로그인 기능 추가`
