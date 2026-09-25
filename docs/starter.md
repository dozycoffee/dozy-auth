# 스타터와 테스트 모듈

`auth-spring-boot-starter`와 `auth-test`가 서비스에 약속하는 것입니다. 이 문서는 구현 명세이며, 서비스 개발자용 사용 설명은 Notion "서비스 연동 가이드"에 있습니다. **이 문서가 바뀌면 Notion 가이드도 갱신합니다.**

- 토큰 검증 규칙 자체는 [token.md §6](token.md#6-검증-규칙)이 기준입니다.
- 공유 타입(`AuthenticatedPrincipal` 등)은 [token.md §10](token.md#10-공유-타입-auth-core)에 있습니다.

## 1. 배포와 호환

| 항목 | 규칙 |
|---|---|
| 좌표 | `com.dozycoffee.auth:auth-core`, `com.dozycoffee.auth:auth-spring-boot-starter`, `com.dozycoffee.auth:auth-test` |
| 저장소 | GitHub Packages `https://maven.pkg.github.com/dozycoffee/dozy-auth` |
| 버전 | 세 모듈이 한 버전. SemVer. 토큰 계약의 major 변경은 스타터 major 변경 ([token.md §11](token.md#11-호환성)) |
| 대상 | Kotlin, Spring Boot 4.1, JVM 17 이상 |
| 공개 API | Kotlin `explicitApi()`. 공개 선언을 바꾸면 버전에 반영 |
| Spring 버전 | 스타터는 Spring Boot BOM을 배포 메타데이터에 싣지 않습니다. 서비스의 Spring 버전을 바꾸지 않기 위해서입니다 |

## 2. 설정

| 속성 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `dozy.auth.audience` | ✅ | | 이 서비스의 audience (`wms`, `catalog`, `store`) |
| `dozy.auth.accepted-realms` | ✅ | | 받을 realm 목록 ([token.md §6](token.md#6-검증-규칙) 서비스별 허용 realm) |
| `dozy.auth.issuer-base-uri` | ✅ | | Auth 주소. 허용 issuer는 `{base}/realms/{realm}` |
| `dozy.auth.jwk-set-uri` | | `{issuer-base-uri}/.well-known/jwks.json` | JWKS 주소 |
| `dozy.auth.clock-skew` | | `policy.clock-skew` 값 | 시계 오차 허용 |
| `dozy.auth.public-paths` | | `[]` | 인증 없이 허용할 경로 패턴 |
| `dozy.auth.method-security` | | `true` | `@PreAuthorize` 활성화 |
| `dozy.auth.client.enabled` | | `false` | system token 클라이언트 사용 ([§6](#6-서비스-간-호출)) |
| `dozy.auth.client.client-id` | 클라이언트 사용 시 | | system client id |
| `dozy.auth.client.client-secret` | 클라이언트 사용 시 | | 비밀 관리 도구에서 주입 |

- 필수 속성이 없으면 기동에 실패합니다.
- 설정 메타데이터(`additional-spring-configuration-metadata.json` 또는 설정 프로세서)를 함께 배포해 IDE 자동완성이 되게 합니다.

## 3. 제공하는 빈

모든 빈은 `@ConditionalOnMissingBean`이라 서비스가 교체할 수 있습니다.

| 빈 | 동작 |
|---|---|
| `JwtDecoder` | RS256 고정, JWKS 캐시, 모르는 `kid`면 재조회(최소 간격 있음). 검증기 체인은 [token.md §6](token.md#6-검증-규칙)의 2~9 |
| `JwtAuthenticationConverter` | `roles` 중 `{audience}:`로 시작하는 것만 골라 prefix를 떼고 `ROLE_{code}` 권한으로 변환. principal은 `AuthenticatedPrincipal` |
| `SecurityFilterChain` | 서비스에 없을 때만. stateless, CSRF 비활성, `public-paths` 외 모든 요청 인증 필요 |
| `AuthenticationEntryPoint`, `AccessDeniedHandler` | [§5](#5-에러-응답) 형식으로 응답 |
| `dozyAuth` | SpEL 헬퍼 ([§4](#4-인가-도구)) |
| `@CurrentPrincipal` 인자 리졸버 | 컨트롤러 인자에 `AuthenticatedPrincipal` 주입 |

**권한 변환 예시** (WMS, `audience = wms`)

| 토큰 `roles` | 변환 결과 |
|---|---|
| `wms:inbound_manager` | `ROLE_inbound_manager` |
| `catalog:menu_editor` | 무시 |

## 4. 인가 도구

```kotlin
@PreAuthorize("hasRole('inbound_manager')")
@PostMapping("/inbounds")
fun create(@CurrentPrincipal principal: AuthenticatedPrincipal, ...)

@PreAuthorize("@dozyAuth.isType('PARTNER')")
@GetMapping("/my/stores")
fun myStores(@CurrentPrincipal principal: AuthenticatedPrincipal)
```

| `dozyAuth` 메서드 | 결과 |
|---|---|
| `isType(type: String)` | 현재 principal type이 같으면 true. 값은 `PrincipalType` 이름 |

- 여러 realm을 받는 서비스(Store)는 모든 API에 type 조건을 붙이는 것을 규칙으로 안내합니다.

- 스타터는 기능 인가 도구만 제공합니다. 리소스 인가(소유 여부)는 서비스가 직접 구현합니다.

## 5. 에러 응답

형식은 [api/conventions.md §4](api/conventions.md#4-에러-응답)와 같습니다.

| 상황 | status | code |
|---|---|---|
| 토큰 없음·검증 실패 (허용되지 않은 realm·audience 포함) | 401 | `UNAUTHENTICATED` |
| role 또는 `dozyAuth` 조건 불만족 | 403 | `FORBIDDEN` |

- `401`에는 `WWW-Authenticate: Bearer` 헤더를 넣습니다.
- `detail`에 검증 실패 이유를 넣지 않습니다. 서버 로그에만 남깁니다.

## 6. 서비스 간 호출

`dozy.auth.client.enabled=true`이면 system token을 자동으로 붙이는 클라이언트를 제공합니다.

| 항목 | 내용 |
|---|---|
| 빈 | `@Qualifier("dozySystemRestClient")` `RestClient.Builder` |
| 토큰 발급 | `{issuer-base-uri}/realms/internal/token`, client credentials, `client_secret_basic` |
| 캐시 | 만료 직전까지 재사용하고 만료 전에 새로 발급 |
| 구현 | Spring Security OAuth2 Client의 client credentials 흐름 |

사용자 토큰을 다른 서비스로 전달하는 기능은 제공하지 않습니다.

## 7. auth-test

### 7.1 `@WithDozyPrincipal`

MockMvc 테스트에서 인증된 사용자를 만듭니다. JWT를 만들지 않고 SecurityContext에 바로 넣습니다.

| 속성 | 기본값 | 설명 |
|---|---|---|
| `type` | `EMPLOYEE` | `PrincipalType` |
| `id` | `1` | principal id |
| `realm` | type에 맞는 realm | `Realm` |
| `roles` | `[]` | `{audience}:{code}` 형식. 스타터와 같은 규칙으로 변환 |

### 7.2 `DozyTestTokens`

통합 테스트에서 실제 검증 체인을 거치는 토큰을 만듭니다.

```kotlin
val token = DozyTestTokens.issue(
    type = PrincipalType.PARTNER,
    id = 7,
    realm = Realm.PARTNER,
    audience = listOf("store"),
    roles = emptyList(),
)
```

- 테스트용 RSA 키로 서명하고, 테스트 컨텍스트에서 스타터가 이 키의 JWKS를 보도록 자동 설정합니다.
- 만료된 토큰, 다른 realm 토큰 등 실패 경우를 만드는 옵션을 둡니다.
- 테스트 키는 테스트 classpath에서만 쓰며 운영 키와 섞지 않습니다.
