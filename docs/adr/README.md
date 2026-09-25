# ADR

결정과 그 이유입니다. 평소에는 이 목록만 보고, 필요한 결정만 열어봅니다.

- 결정이 바뀌면 기존 파일은 고치지 않고 새 ADR을 추가한 뒤, 기존 파일의 상태를 `대체됨(→ 번호)`으로 바꿉니다.
- 수치는 ADR에 쓰지 않고 [domain.md §2](../domain.md#2-정책-값)의 정책 이름으로 참조합니다.
- 파일 이름은 `{번호 4자리}-{영문-kebab-case}.md`입니다.

| 번호 | 결정 | 상태 | 요약 |
|---|---|---|---|
| [0001](0001-principal-common-table.md) | 계정은 공통 principal 테이블과 타입별 profile로 나눈다 | 채택 | 공통 `principal` + 타입별 profile 테이블(PK=FK) |
| [0002](0002-bigint-principal-id.md) | principal id는 bigint 순번이고 토큰에는 type과 id를 함께 싣는다 | 채택 | bigint identity id, `sub="{type}:{id}"` + `principalType`/`principalId` |
| [0003](0003-multi-audience-token.md) | 토큰 하나에 여러 audience를 싣고 role은 audience로 네임스페이스한다 | 채택 | `aud` 배열, role은 `{audience}:{code}` |
| [0004](0004-no-immediate-revocation.md) | access token 즉시 차단은 지금 지원하지 않는다 | 채택 | access token은 만료까지 유효. 필요해지면 차단 이벤트 + 서비스별 블랙리스트 |
| [0005](0005-partner-without-roles.md) | 파트너에게는 Auth role을 주지 않고 매장 관계는 Store가 관리한다 | 채택 | 점주-매장 관계는 Store 서비스 소유 |
| [0006](0006-direct-login-api.md) | 로그인은 직접 로그인 API로 하고 PKCE는 확장안으로 둔다 | 채택 | 각 앱이 `POST /realms/{realm}/login` 호출. Authorization Code + PKCE는 필요해질 때 |
| [0007](0007-self-issued-jwt.md) | 토큰 발급은 Spring Security와 Nimbus로 직접 구현한다 | 채택 | Spring Authorization Server 미사용. `NimbusJwtEncoder`로 서명 |
| [0008](0008-shared-library-modules.md) | 서버와 서비스용 라이브러리를 한 저장소의 멀티모듈로 둔다 | 채택 | `dozy-auth` 저장소: auth-core / auth-server / auth-spring-boot-starter / auth-test |
| [0009](0009-owner-admin-governance.md) | Auth 관리 권한은 owner 한 명과 admin 여러 명으로 나눈다 | 채택 | `auth:owner`(1명, 양도만) > `auth:admin`(owner가 임명). admin은 owner·admin을 건드릴 수 없음 |
| [0010](0010-owner-bootstrap-and-recovery.md) | 최초 owner는 설정 이메일로 초대하고, 복구는 수동 SQL로 한다 | 채택 | `BOOTSTRAP_OWNER_EMAIL`로 초대. 복구는 인프라 관리자의 SQL 절차 |
| [0011](0011-credential-per-method.md) | 크리덴셜은 인증 방식별 테이블로 나눈다 | 채택 | `password_credential`, 추후 `external_identity`, TOTP, passkey |
| [0012](0012-unified-verification.md) | 1회용 토큰은 verification 테이블 하나로 관리한다 | 채택 | 초대·가입 인증·재설정·owner 양도를 `purpose`로 구분 |
| [0013](0013-refresh-session-per-login.md) | refresh 세션은 로그인당 한 행으로 두고 직전 토큰까지만 재사용을 탐지한다 | 채택 | 현재·직전 해시를 덮어쓰는 rotation, 교체 직후 유예 |
| [0014](0014-token-storage-in-client.md) | access token은 앱 메모리, refresh token은 HttpOnly 쿠키에 둔다 | 채택 | refresh token은 HttpOnly 쿠키, 경로는 realm 단위 |
| [0015](0015-shared-jwks.md) | JWKS는 모든 realm이 공유하고 realm은 iss로 구분한다 | 채택 | `/.well-known/jwks.json` 하나, realm별 issuer |
| [0016](0016-no-oidc.md) | OIDC는 쓰지 않고 표시 정보는 /me로 제공한다 | 채택 | id token 없음. 앱은 `GET /realms/{realm}/me` 호출 |
| [0017](0017-role-definition-by-admin.md) | role 정의는 admin이 관리 화면에서 등록한다 | 채택 | audience별 role 등록·수정·삭제 API. code는 변경 불가 |
| [0018](0018-no-forced-password-change.md) | 비밀번호 강제 변경 기능을 두지 않는다 | 채택 | 임시 비밀번호 대신 재설정 메일 |
| [0019](0019-postgresql-and-flyway.md) | 저장소는 PostgreSQL, 마이그레이션은 Flyway SQL로 한다 | 채택 | PostgreSQL 18 + Flyway. 세션도 RDB |
| [0020](0020-no-physical-deletion.md) | 계정은 물리 삭제하지 않고 비활성화하며 개인정보를 파기한다 | 채택 | `DEACTIVATED` + 개인정보 마스킹, 보관 기간 뒤 정리 |
| [0021](0021-kotlin-spring-boot-mvc.md) | Kotlin, JDK 21, Spring Boot 4.1(MVC)로 만든다 | 채택 | 서버 JDK 21, 라이브러리 JVM 17 타깃 |
| [0022](0022-exposed-dsl.md) | DB 접근은 Exposed DSL로 한다 | 채택 | Exposed 1.x DSL, `exposed-spring-boot4-starter`. DAO와 JPA 미사용 |
| [0023](0023-hexagonal-layer-first.md) | 서버는 계층 우선 헥사고날 구조로 만들고 도메인끼리 참조하지 않는다 | 채택 | domain / application(port in·out, service) / adapter(in·out) / config |
| [0024](0024-in-memory-rate-limit-and-scheduler.md) | 요청 제한은 인메모리 Bucket4j, 배치는 @Scheduled로 한다 | 채택 | 인스턴스 한 대 전제. 다중화 시 Redis, ShedLock |
| [0025](0025-konsist-architecture-tests.md) | 아키텍처 규칙은 Konsist로 검사하고 detekt는 나중에 도입한다 | 채택 | Konsist 처음부터, ktlint로 포맷, detekt 보류 |
| [0026](0026-problem-details-errors.md) | 에러 응답은 RFC 9457 Problem Details에 code와 traceId를 더한다 | 채택 | `application/problem+json` + `code` + `traceId`. Auth 전용 규칙 |
| [0027](0027-argon2id-password-hash.md) | 비밀번호 해시는 argon2id로 한다 | 채택 | Spring Security `Argon2PasswordEncoder` + Bouncy Castle |

## 양식

```markdown
# 0000. 결정 제목

- 상태: 채택 | 대체됨(→ 0000)
- 날짜: YYYY-MM-DD

## 맥락
## 결정
## 검토한 대안
## 결과
```
