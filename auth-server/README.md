# auth-server

Dozy Auth 서버입니다. 로그인, 토큰 발급, 계정·role 관리 API를 제공합니다.

## 명세

| 문서 | 내용 |
|---|---|
| [docs/architecture.md](../docs/architecture.md) | 계층, 의존 규칙, 이름 규칙, 코드 규칙 |
| [docs/domain.md](../docs/domain.md) | 정책 수치와 도메인 규칙 |
| [docs/api/](../docs/api/conventions.md) | API 공통 규칙과 엔드포인트 |
| [docs/data-model.md](../docs/data-model.md) | 테이블과 마이그레이션 기준 |
| [docs/token.md](../docs/token.md) | 발급할 토큰 형식 |
| [docs/configuration.md](../docs/configuration.md) | 환경 변수, 서명 키, 프로필 |

## 실행

```bash
./gradlew :auth-server:bootRun --args='--spring.profiles.active=local'
```

- `bootRun`의 작업 폴더는 저장소 루트입니다. `compose.yaml`과 `.local/`을 루트에서 찾습니다.
- 프로필별 동작은 [configuration.md §4](../docs/configuration.md#4-프로필)를 따릅니다.
- 로컬에서 처음 owner 계정을 만드는 방법은 [루트 README](../README.md#첫-owner-계정-만들기)에 있습니다.

## 구조

패키지 구성은 [architecture.md §5](../docs/architecture.md#5-auth-server-구조)를 따릅니다.

```text
src/main/kotlin/com/dozycoffee/auth/server/    domain, application, adapter, config
src/main/resources/
├─ application.yaml                           공통 설정 (환경 변수 연결)
├─ application-{profile}.yaml                 프로필별 설정
├─ db/migration/                              Flyway 마이그레이션
└─ templates/mail/                            메일 템플릿 (Thymeleaf)
src/test/kotlin/com/dozycoffee/auth/server/    테스트
```

(프로필별 설정 파일은 `local`, `dev`, `prod`가 있습니다. 메일 템플릿은 종류마다 텍스트(`.txt`)와 HTML(`.html`) 한 쌍입니다.)

## 무엇을 어디에 추가하나

| 추가할 것 | 먼저 고칠 명세 | 코드 위치 |
|---|---|---|
| API | `docs/api/*.md` 중 해당 파일 | `adapter/inbound/web/{auth,account,admin,internal,dev}` → `application/port/inbound/{영역}` → `application/service/{영역}` ([architecture.md §5](../docs/architecture.md#5-auth-server-구조)) |
| 도메인 규칙 | `docs/domain.md` (새 규칙 ID) | `domain/{도메인}` |
| 테이블·컬럼 | `docs/data-model.md` | `src/main/resources/db/migration/V{번호}__{설명}.sql`, `adapter/outbound/persistence/table/*Table` |
| 에러 코드 | `docs/api/conventions.md` §11 | 도메인 예외 (`AuthException` 하위) |
| 감사 action | `docs/domain.md` AUD-01 | `domain/audit` |
| 설정 키·환경 변수 | `docs/configuration.md` | `application.yaml`, `config/` |
| 정책 수치 | `docs/domain.md` §2 | 정책 상수 (속성으로 노출할 때는 `dozy.auth.policy.*`) |
| 메일 | 해당 API 문서 | `adapter/outbound/mail`, `templates/mail/` |

- 적용된 마이그레이션 파일은 고치지 않고 새 번호로 추가합니다.
- 규칙을 구현한 코드와 테스트 이름에는 규칙 ID를 씁니다 (예: `SES-03`).

## 제약

- JDK 21.
- 계층 간 의존과 도메인 간 참조 규칙은 Konsist 테스트가 검사합니다. 테스트가 실패하면 규칙을 우회하지 말고 구조를 고칩니다.
- DB 접근은 Exposed DSL로 `adapter/outbound/persistence` 안에서만 합니다.

## 테스트

```bash
./gradlew :auth-server:test
```

- 테스트 종류와 작성 규칙은 [docs/testing.md](../docs/testing.md)를 따릅니다.
- 아키텍처 테스트만 돌리려면 `./gradlew :auth-server:test --tests '*ArchitectureTest*'`. 규칙 목록은 [architecture.md §6.3](../docs/architecture.md#63-아키텍처-테스트)에 있습니다.
- 공용 fixture(`TokenFixtures`, `TestSigningKeys`)는 `src/test/kotlin/com/dozycoffee/auth/server/support/`에 있습니다. API 테스트는 `TestEmployees`(실제 DB에 직원·비밀번호·role 생성)와 `TestAccessTokens`(서버 서명 키로 토큰 발급)를 `@Import`해 씁니다. 예시는 `adapter/inbound/web/auth/LoginApiTest`입니다.
- 관리 API를 실제 순서대로 잇는 전체 흐름 테스트는 `FullFlowIntegrationTest`입니다. 단계가 앞 단계의 계정과 토큰을 이어 쓰므로 클래스 단위로 돌립니다: `./gradlew :auth-server:test --tests '*FullFlowIntegrationTest'`
- Testcontainers를 쓰는 테스트는 Docker가 필요합니다.
- 영속성 어댑터 테스트는 `@PersistenceAdapterTest`(`support/`)를 붙이면 Flyway가 적용된 PostgreSQL에서 테이블 객체(`*Table`)로 바로 insert·select할 수 있습니다. 예시는 `adapter/outbound/persistence/table/PrincipalTableTest`입니다.
- 로컬에서 컨테이너를 실행 사이에도 남기려면 `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true`를 둡니다. CI에서는 쓰지 않습니다.
