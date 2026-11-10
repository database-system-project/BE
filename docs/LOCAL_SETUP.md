# 로컬 환경 설정

실제 비밀번호·JWT 키·로컬 주소는 BE 저장소 루트의 `.env`에서 관리한다. `.env`와 `application*.yml`·`application*.yaml`은 Git에서 제외한다. 공통 실행 설정은 비밀값이 없는 `application.properties`, 테스트 설정은 `application-test.properties`로 공유한다.

## 최초 준비

JDK 21과 Docker Compose를 준비하고 BE 저장소 루트에서 실행한다.

```sh
./gradlew initLocalEnv
```

Windows에서는 `gradlew.bat initLocalEnv`를 사용한다. Gradle은 `.env.example`을 기준으로 `.env`를 만들고 PostgreSQL 비밀번호와 JWT 키를 각각 새로 생성한다. 기존 `.env`가 있으면 덮어쓰지 않고 종료한다. 비밀값은 터미널에 출력하지 않으며 POSIX 환경에서는 파일 권한을 0600으로 지정한다.

`.env.example`에는 실제 비밀번호가 없다. `.env`는 각자 생성하며 GitHub·메신저로 공유하지 않는다.

## 환경 변수

| 변수 | 기본 설정과 용도 |
| --- | --- |
| POSTGRES_DB | hospital. Docker Compose가 처음 만들 업무 DB 이름 |
| POSTGRES_USER | hospital. PostgreSQL 사용자 |
| POSTGRES_PASSWORD | 초기화 명령이 생성한 무작위 비밀번호 |
| POSTGRES_PORT | 15432. PC에서 PostgreSQL에 접속하는 포트 |
| JWT_SECRET | 초기화 명령이 생성한 32바이트 난수의 64자리 hex 문자열 |
| JWT_TTL_SECONDS | 1800. 로그인 토큰 유효기간(초) |
| CORS_ALLOWED_ORIGINS | http://localhost:8443,http://127.0.0.1:8443 |
| SERVER_ADDRESS | 127.0.0.1. 백엔드를 실행한 PC에서 접근 |
| SERVER_PORT | 8080. 백엔드 HTTP 포트 |

Spring과 Docker Compose가 같은 POSTGRES_* 값을 사용하므로 비밀번호를 두 군데에 중복 작성하지 않는다. 기존 외부·직접 설치 DB를 사용한다면 해당 DB의 실제 계정·포트·이름에 맞춰 설정한다. 필요할 때 DB_URL·DB_USERNAME·DB_PASSWORD로 Spring 연결만 별도 지정할 수 있다. 비밀번호를 `.env`에서 바꿔도 이미 만들어진 DB 계정의 비밀번호가 자동으로 바뀌지는 않는다.

## DB와 백엔드 실행

```sh
docker compose up -d --wait
./gradlew clean bootRun
```

기본 프로필은 local이다. 기존처럼 `./gradlew bootRun --args='--spring.profiles.active=local'`로 명시해도 된다. `.env`에서 값을 읽기 때문에 터미널에 비밀번호를 붙여 넣거나 export할 필요가 없다.

- 백엔드: http://localhost:8080
- 상태 확인: http://localhost:8080/actuator/health
- 로컬 Swagger: http://localhost:8080/swagger-ui.html
- 프런트: http://localhost:8443 (프런트 저장소의 기본 Vite 포트)

프런트는 API 기본 주소를 http://localhost:8080으로 사용한다. CORS 허용 목록에는 프런트 주소를 넣고, 목록을 바꿨다면 백엔드를 재시작한다. 로그인·회원가입 외 보호된 API에는 기존처럼 JWT가 필요하다.

## 테스트

최초 한 번 테스트 전용 DB를 만든다. POSTGRES_USER를 바꿨다면 아래 사용자 이름도 맞춘다.

```sh
docker compose exec -T postgres createdb -U hospital hospital_test
./gradlew test
```

테스트는 별도 hospital_test DB에 연결하고 데이터를 초기화한다. 기본 테스트 계정·포트·비밀번호는 로컬 POSTGRES_* 설정을 따른다. 다른 테스트 DB는 TEST_DB_URL·TEST_DB_USERNAME·TEST_DB_PASSWORD로 지정한다. CI는 `.env` 없이 GitHub Actions의 테스트 전용 PostgreSQL과 테스트 프로필을 사용한다.

## Git과 빌드에서 제외되는 파일

- `.env`, `.env.*`는 제외하고 비밀값이 없는 `.env.example`만 추적한다.
- 애플리케이션 YAML은 추적을 해제했으며 processResources·processTestResources에서도 제외한다. PC에 남아 있는 YAML이 실행 JAR에 복사되지 않는다.
- Compose와 GitHub Actions YAML은 공통 실행·CI 정의로 유지하며 실제 비밀번호는 포함하지 않는다.
- 이번 변경은 새 PR에서 파일 추적을 해제하는 방식이다. 과거 Git 이력은 강제로 다시 쓰지 않는다. 확인한 설정 파일 이력에서는 테스트 전용 상수를 제외한 설명되지 않은 고정 비밀번호·키가 발견되지 않았다.
