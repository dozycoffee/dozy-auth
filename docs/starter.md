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
| 대상 | Kotlin, Spring Boot 4.1, JVM 17 이상. Spring MVC와 WebFlux(코루틴, Reactor) 모두 ([ADR-0030](adr/0030-starter-supports-mvc-and-webflux.md)) |
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

모든 빈은 `@ConditionalOnMissingBean`이라 서비스가 교체할 수 있습니다. 앱 종류(Spring MVC, WebFlux)에 맞는 쪽만 등록되며, 동작은 같습니다.

| 역할 | Spring MVC | WebFlux | 동작 |
|---|---|---|---|
| 토큰 해독·검증 | `JwtDecoder` | `ReactiveJwtDecoder` | RS256 고정. 검증기 체인은 [token.md §6](token.md#6-검증-규칙)의 2~9. `iat`는 필수이고 `clock-skew`보다 미래면 거부 |
| JWKS 조회 | 위 디코더 안 | 위 디코더 안 | [`policy.jwks-cache-max-age`](domain.md#2-정책-값) 동안 캐시. 모르는 `kid`면 재조회하되, [`policy.jwks-refetch-min-interval`](domain.md#2-정책-값)마다 최대 두 번(처음 조회 + 재조회 한 번)으로 제한. WebFlux는 이벤트 루프를 막지 않도록 별도 스케줄러에서 조회 |
| 권한 변환 (빈 이름 `dozyJwtAuthenticationConverter`) | `Converter<Jwt, AbstractAuthenticationToken>` | `Converter<Jwt, Mono<AbstractAuthenticationToken>>` | `roles` 중 `{audience}:`로 시작하는 것만 골라 prefix를 떼고 `ROLE_{code}` 권한으로 변환. 결과는 `DozyAuthenticationToken`이며 principal은 `AuthenticatedPrincipal`. 이름으로 교체 |
| 필터 체인 | `SecurityFilterChain` | `SecurityWebFilterChain` | 서비스에 없을 때만. stateless, CSRF 비활성, `public-paths` 외 모든 요청 인증 필요 |
| 401·403 | `AuthenticationEntryPoint`, `AccessDeniedHandler` | `ServerAuthenticationEntryPoint`, `ServerAccessDeniedHandler` | [§5](#5-에러-응답) 형식으로 응답 |
| `dozyAuth` | `DozyAuth` | `DozyReactiveAuth` | SpEL 헬퍼 ([§4](#4-인가-도구)) |
| 메서드 보안 | `@EnableMethodSecurity` | `@EnableReactiveMethodSecurity` | `dozy.auth.method-security`가 `true`일 때 |

- `@CurrentPrincipal`은 Spring Security `@AuthenticationPrincipal`을 메타 애노테이션으로 쓰므로 별도 빈이 없습니다.
- 필터 체인만 교체할 때는 위 빈(디코더, 변환기, 401·403 핸들러)을 주입받아 쓰면 토큰 검증 규칙이 그대로 유지됩니다. stateless, CSRF, `public-paths`는 교체한 쪽이 다시 설정합니다.
- 슬라이스 테스트(`@WebFluxTest`, `@WebMvcTest`)에서도 자동 설정이 켜집니다.
- 자동 설정과 다른 키 출처가 필요하면(테스트 키, Auth 서버의 메모리 키) `DozyJwtDecoders.create(properties, jwkSource, clock)`(Spring MVC), `DozyReactiveJwtDecoders.create(...)`(WebFlux)로 같은 검증 규칙의 디코더를 만듭니다. `jwkSource`를 생략하면 JWKS 주소에서 받습니다.
- **Auth 서버용** `DozyJwtDecoders.createWithoutAudienceCheck(...)`, `DozyReactiveJwtDecoders.createWithoutAudienceCheck(...)`: 인자는 `create`와 같고, [token.md §6](token.md#6-검증-규칙)의 8(`aud`)만 빼고 같은 규칙으로 검증합니다. `aud` claim은 없거나 비어 있거나 다른 audience만 있어도 거부하지 않습니다. Auth의 `aud`를 검사하지 않는 경로([api/conventions.md §2](api/conventions.md#2-인증-방식))에 씁니다. 서비스는 쓰지 않으며, 자동 설정의 디코더나 `create`를 씁니다. `dozy.auth.audience`는 이때도 필수이고, 권한 변환기가 그 audience의 role만 권한으로 바꾸는 데 씁니다.
- 토큰이 잘못된 경우(서명 불일치, 모르는 `kid`, 재조회 제한에 걸린 `kid`, 검증기 실패)는 401입니다. JWKS를 받지 못한 경우만 서버 오류입니다.
- `Clock` 빈이 하나 있으면 `exp`·`iat` 검증에 그 시계를 씁니다 (테스트의 고정 시계 등). 없거나 여러 개면 UTC 시스템 시계를 씁니다. 서비스가 `Clock` 빈을 만들 필요는 없습니다.

**권한 변환 예시** (WMS, `audience = wms`)

| 토큰 `roles` | 변환 결과 |
|---|---|
| `wms:inbound_manager` | `ROLE_inbound_manager` |
| `catalog:menu_editor` | 무시 |

## 4. 인가 도구

```kotlin
@PreAuthorize("hasRole('inbound_manager')")
@PostMapping("/inbounds")
suspend fun create(@CurrentPrincipal principal: AuthenticatedPrincipal, ...)

@PreAuthorize("@dozyAuth.isType('PARTNER')")
@GetMapping("/my/stores")
suspend fun myStores(@CurrentPrincipal principal: AuthenticatedPrincipal)
```

| `dozyAuth` 메서드 | 결과 |
|---|---|
| `isType(type: String)` | 현재 principal type이 같으면 true. 값은 `PrincipalType` 이름. WebFlux에서는 `Mono<Boolean>`이며 SpEL 사용법은 같음 |

- WebFlux에서 `@PreAuthorize`는 `suspend` 함수나 `Mono`·`Flux`를 돌려주는 메서드에만 붙입니다. 값을 바로 돌려주는 일반 함수에 붙이면 호출할 때 오류가 납니다 (Spring Security reactive 메서드 보안의 제약).

- 여러 realm을 받는 서비스(Store)는 모든 API에 type 조건을 붙이는 것을 규칙으로 안내합니다.

- 스타터는 기능 인가 도구만 제공합니다. 리소스 인가(소유 여부)는 서비스가 직접 구현합니다.

## 5. 에러 응답

형식은 [api/conventions.md §4](api/conventions.md#4-에러-응답)와 같습니다.

| 상황 | status | code |
|---|---|---|
| 토큰 없음·검증 실패 (허용되지 않은 realm·audience 포함) | 401 | `UNAUTHENTICATED` |
| role 또는 `dozyAuth` 조건 불만족 | 403 | `FORBIDDEN` |

- 본문 필드는 `type`, `title`, `status`, `instance`(요청 경로), `code`, `traceId`이고 `detail`은 없습니다. `Content-Type`은 `application/problem+json`입니다.
- `401`에는 `WWW-Authenticate: Bearer` 헤더를 넣습니다.
- `detail`에 검증 실패 이유를 넣지 않습니다. 실패 이유는 `com.dozycoffee.auth.starter` 로거의 debug 로그에만 남깁니다.
- `traceId`와 응답 헤더 `X-Trace-Id`는 같은 값입니다. 아래 순서로 처음 있는 값을 씁니다.
  1. Micrometer Tracing의 현재 trace id
  2. 서비스의 필터가 이미 응답에 붙인 `X-Trace-Id` (서비스가 정한 값이므로 그대로 씀)
  3. 요청의 `X-Trace-Id` (영문·숫자·하이픈 64자 이내만)
  4. 새로 만든 값 (16바이트 난수의 hex)

**본문 직렬화**

- 본문은 Spring `ProblemDetail`로 만들고 서비스의 변환기로 씁니다. 서비스가 직렬화 설정을 바꿔도 서비스 자신의 에러 응답과 같은 방식으로 쓰기 위해서입니다. `code`와 `traceId`는 `ProblemDetail`의 `properties`이며 변환기가 최상위 필드로 펼칩니다.

| 앱 | 쓰는 변환기 |
|---|---|
| Spring MVC | `RequestMappingHandlerAdapter`의 `HttpMessageConverter` 중 `ProblemDetail`을 `application/problem+json`으로 쓸 수 있는 첫 번째 |
| WebFlux | `ServerCodecConfigurer` 빈의 `HttpMessageWriter` 중 `ProblemDetail`을 `application/problem+json`으로 쓸 수 있는 첫 번째 |

- Spring Boot가 만드는 Jackson 3(`JsonMapper`)·Jackson 2(`Jackson2ObjectMapperBuilder`) 변환기와 Spring의 기본 Jackson 변환기는 `ProblemDetail`의 `properties`를 펼칩니다. 서비스가 이 설정을 거치지 않고 `JsonMapper`·`ObjectMapper`를 직접 만들어 변환기에 넣었다면 `ProblemDetailJacksonMixin`을 등록해야 합니다 (서비스 자신의 `ProblemDetail` 응답에도 필요).
- 쓸 수 있는 변환기가 없으면(Jackson이 없는 서비스, Spring MVC가 아닌 servlet 앱 등) 같은 필드와 값의 JSON을 스타터가 직접 씁니다. 처음 한 번 warn 로그를 남깁니다. 기동은 실패시키지 않습니다. 401·403은 변환기 없이도 쓸 수 있는 고정 형식이라, 기동을 막으면 Jackson을 쓰지 않는 서비스가 스타터를 쓸 수 없게 되기 때문입니다.

## 6. 서비스 간 호출

`dozy.auth.client.enabled=true`이면 system token을 자동으로 붙이는 클라이언트 builder를 제공합니다. 앱 종류(Spring MVC, WebFlux)에 맞는 쪽만 등록되며, 동작은 같습니다 ([ADR-0030](adr/0030-starter-supports-mvc-and-webflux.md)).

| 앱 | 빈 이름 (qualifier) | 타입 |
|---|---|---|
| Spring MVC | `dozySystemRestClient` | `RestClient.Builder` |
| WebFlux | `dozySystemWebClient` | `WebClient.Builder` |

```kotlin
@Component
class StoreClient(@Qualifier("dozySystemWebClient") builder: WebClient.Builder) {
    private val client = builder.baseUrl("https://store.internal").build()
}
```

| 항목 | 내용 |
|---|---|
| 토큰 발급 | `{issuer-base-uri}/realms/internal/token`, client credentials, `client_secret_basic` ([api/internal.md](api/internal.md#서비스-토큰-발급)) |
| 캐시 | 앱 하나에 토큰 하나를 두고 모든 builder가 함께 씁니다. 만료 60초 전까지 재사용하고, 그 뒤 첫 호출에서 새로 발급합니다 |
| 발급 제한 시간 | 토큰 엔드포인트 연결·응답 5초 |
| 구현 | Spring Security OAuth2 Client의 client credentials 흐름 |

- `enabled=true`인데 `client-id`나 `client-secret`이 없으면 기동에 실패합니다. 실패 메시지에 secret 값을 넣지 않습니다.
- 사용자 토큰을 다른 서비스로 전달하는 기능은 제공하지 않습니다.

**builder**

- 주입할 때마다 새 builder입니다 (prototype). `baseUrl` 등을 바꿔도 다른 주입에는 영향이 없습니다.
- Spring Boot가 만든 기본 builder(`RestClient.Builder`, `WebClient.Builder` 빈)가 하나 있으면 복제해서 시작하므로 서비스의 메시지 변환기·codec·관측(trace 전파) 설정이 그대로 적용됩니다. 원본 builder는 바꾸지 않습니다. 없으면 Spring 기본 builder로 시작합니다.
- 토큰을 붙이는 interceptor(Spring MVC)와 filter(WebFlux)는 builder의 마지막에 더합니다. 서비스가 builder에 더한 것보다 나중에 실행되며, `Authorization` 헤더를 system token으로 덮어씁니다.
- 타입만으로는 주입되지 않습니다 (`@Bean(defaultCandidate = false)`). qualifier 없이 `RestClient.Builder`·`WebClient.Builder`를 주입받는 곳은 Spring Boot의 기본 builder를 그대로 받으므로 system token이 의도하지 않은 곳으로 나가지 않습니다.
- 같은 이름의 빈을 정의하면 스타터 빈이 빠집니다.

**서비스의 OAuth2 Client 설정과의 관계**

- 스타터는 `spring-security-oauth2-client`를 runtime 의존성으로 가져갑니다. Spring Boot의 OAuth2 Client 자동 설정 모듈은 가져가지 않으므로 클라이언트를 쓰지 않는 서비스의 설정은 바뀌지 않습니다. 스타터의 공개 API에는 OAuth2 Client 타입이 없습니다.
- 스타터는 `ClientRegistrationRepository`, `OAuth2AuthorizedClientService`, `OAuth2AuthorizedClientManager`(reactive 포함)를 빈으로 등록하지 않고 클라이언트 안에만 둡니다. registration id는 `dozy-auth`이며 서비스의 저장소에는 들어가지 않습니다. 서비스가 다른 API용 OAuth2 Client 설정을 가지고 있어도 서로 바꾸거나 주입을 모호하게 만들지 않습니다.

**발급 실패**

- 토큰을 발급받지 못하면(`invalid_client`, Auth 연결 실패·제한 시간 초과 등) 호출을 보내지 않고 `DozySystemTokenException`으로 실패합니다. Spring MVC는 호출에서 예외를 던지고, WebFlux는 오류 신호를 보냅니다.
- `DozySystemTokenException.error`는 토큰 엔드포인트의 OAuth 오류 코드입니다. 응답을 받지 못했으면 원인 예외의 이름입니다. 원인 예외는 `cause`에 있습니다.
- `com.dozycoffee.auth.starter` 로거에 warn 로그를 남깁니다. client id와 오류 코드만 쓰고, client secret, 토큰 원문, `Authorization` 헤더는 로그와 예외 메시지에 남기지 않습니다 ([SEC-03](domain.md#12-민감정보-sec)).
- 실패는 캐시하지 않습니다. 다음 호출에서 다시 발급을 시도합니다.

## 7. auth-test

서비스의 `testImplementation`으로 씁니다. 테스트 키 설정은 Spring 테스트가 만든 컨텍스트(`@SpringBootTest`, `@WebFluxTest`, `@WebMvcTest` 등)에만 적용되므로, 실수로 운영 classpath에 들어가도 운영의 토큰 검증은 바뀌지 않습니다. Spring MVC(MockMvc)와 WebFlux(`WebTestClient`)를 모두 지원합니다.

### 7.1 `@WithDozyPrincipal`

컨트롤러 테스트에서 인증된 사용자를 만듭니다. JWT를 검증하지 않고 SecurityContext에 바로 넣습니다. 슬라이스 테스트(`@WebFluxTest`, `@WebMvcTest`)와 `@SpringBootTest` 모두에서 동작합니다.

| 속성 | 기본값 | 설명 |
|---|---|---|
| `type` | `EMPLOYEE` | `PrincipalType` |
| `id` | `00000000-0000-7000-8000-000000000001` | principal id. 애노테이션 속성은 `UUID` 타입을 쓸 수 없어 UUID 문자열로 받습니다 |
| `roles` | `[]` | `{audience}:{code}` 형식 |

- realm은 `type`이 속한 realm입니다([DOM-01](domain.md#11-realm과-principal-type)). 속성으로 받지 않습니다.
- 속성으로 claim만 채운 JWT를 만들어 스타터의 권한 변환기(`dozyJwtAuthenticationConverter`)에 넣습니다. role 변환이 실제 토큰과 같고, 서비스가 변환기를 교체했으면 교체한 변환기를 씁니다.

### 7.2 `DozyTestTokens`

통합 테스트에서 실제 검증 체인을 거치는 토큰을 만듭니다. 테스트 컨텍스트의 빈이며, `iss`와 기본 `aud`는 서비스 설정(`dozy.auth.*`)을 따릅니다.

```kotlin
@Autowired lateinit var tokens: DozyTestTokens

val token = tokens.issue(roles = listOf("wms:inbound_manager"))
```

| 인자 | 기본값 | 실패 경우를 만들 때 |
|---|---|---|
| `type`, `id`, `roles` | `EMPLOYEE`, `@WithDozyPrincipal`과 같은 id, `[]` | 받지 않는 realm의 type (예: WMS에 `PARTNER`) |
| `realm` | `type`이 속한 realm | 다른 realm (DOM-01 위반) |
| `audience` | 서비스 audience | 다른 audience |
| `issuerBaseUri` | 서비스 `issuer-base-uri` | 다른 Auth 주소 |
| `issuedAt`, `expiresAt` | 지금, 10분 뒤 | 만료, 미래 `iat` |
| `sessionId` | system token이 아니면 임의의 UUID | |
| `signedBy` | `TRUSTED` | `UNTRUSTED` (믿지 않는 키) |

- 형식은 [token.md §3](token.md#3-claims)과 같습니다 (`typ=at+jwt`, RS256, `aud`는 배열).
- 테스트 키는 테스트 JVM에서 한 번 만들어 메모리에만 둡니다. 테스트 컨텍스트에서는 스타터 디코더가 JWKS 주소 대신 이 키를 믿습니다. 서비스가 디코더 빈을 직접 정의했으면 적용되지 않습니다.
- 테스트 키 설정은 Spring 테스트의 `ContextCustomizerFactory`로 등록합니다. 자동 설정이 아니므로 애플리케이션을 직접 실행한 컨텍스트에는 적용되지 않습니다.
