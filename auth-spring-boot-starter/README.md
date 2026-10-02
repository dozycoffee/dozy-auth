# auth-spring-boot-starter

서비스(WMS, Catalog, Store)가 Auth 토큰을 검증하고 role로 인가할 수 있게 하는 Spring Boot 자동 설정입니다.

> 서비스에서 쓰는 방법은 Notion "서비스 연동 가이드"에 자세히 있습니다. 이 README는 설치와 최소 설정만 다룹니다.

## 명세

- [docs/starter.md](../docs/starter.md): 설정 키, 제공 빈, 인가 도구, 에러 응답, 서비스 간 호출. **이 모듈의 기준**
- [docs/token.md §6](../docs/token.md#6-검증-규칙): 토큰 검증 규칙
- [docs/api/conventions.md §4](../docs/api/conventions.md#4-에러-응답): 401·403 응답 형식

## 사용 방법

GitHub Packages에서 받습니다. 읽을 때도 인증이 필요하므로 `read:packages` 권한의 개인 토큰을 `~/.gradle/gradle.properties`에 `gpr.user`, `gpr.token`으로 넣습니다. 버전은 저장소의 Releases에서 확인합니다.

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/dozycoffee/dozy-auth")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GPR_USER")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GPR_TOKEN")
        }
        content { includeGroup("com.dozycoffee.auth") }
    }
}

dependencies {
    implementation("com.dozycoffee.auth:auth-spring-boot-starter:{version}")
    testImplementation("com.dozycoffee.auth:auth-test:{version}")
}
```

최소 설정 (나머지 설정 키는 [starter.md §2](../docs/starter.md#2-설정)):

```yaml
dozy:
  auth:
    audience: wms
    accepted-realms: [internal]
    issuer-base-uri: https://auth.dozycoffee.com
```

다른 서비스를 system token으로 호출하려면 클라이언트를 켜고 `dozySystemWebClient`(WebFlux) 또는 `dozySystemRestClient`(Spring MVC) builder를 qualifier로 주입받습니다 ([starter.md §6](../docs/starter.md#6-서비스-간-호출)). secret은 저장소에 두지 않고 환경 변수 등으로 주입합니다.

```yaml
dozy:
  auth:
    client:
      enabled: true
      client-id: svc-catalog
      client-secret: ${DOZY_AUTH_CLIENT_SECRET}
```

## 구조

Spring MVC와 WebFlux를 모두 지원합니다 ([ADR-0030](../docs/adr/0030-starter-supports-mvc-and-webflux.md)). 앱 종류에 맞는 자동 설정만 켜지고, 검증 규칙과 권한 변환은 같은 코드를 씁니다.

```text
src/main/kotlin/com/dozycoffee/auth/starter/
├─ 공통
│  ├─ DozyAuthProperties             dozy.auth.* 설정과 필수 값 검사
│  ├─ DozyJwtDecoders                서명 검증(RS256), JWKS 캐시·재조회 제한, Spring MVC용 디코더 (공개)
│  ├─ DozyTokenValidators            token.md §6의 3, 6~9 검증기
│  ├─ DozyJwtAuthenticationConverter role 변환, AuthenticatedPrincipal 생성
│  ├─ DozyAuthenticationToken        인증 결과 (principal = AuthenticatedPrincipal)
│  ├─ CurrentPrincipal               컨트롤러 인자
│  ├─ DozyProblems                   401·403 본문(ProblemDetail, 변환기가 없을 때의 JSON), traceId 순서
│  ├─ DozyAuthClientProperties       dozy.auth.client.* 설정 (secret은 toString·기동 실패 메시지에 남기지 않음)
│  ├─ DozySystemClients              system token 클라이언트 공통: 스타터 전용 ClientRegistration, 발급 실패 처리
│  └─ DozySystemTokenException       system token 발급 실패 (공개)
├─ Spring MVC
│  ├─ DozyAuthServletAutoConfiguration
│  ├─ DozyAuth                       dozyAuth SpEL 헬퍼
│  ├─ DozyProblemResponses           401·403 응답 쓰기 (서비스의 HttpMessageConverter)
│  └─ DozySystemClientServletAutoConfiguration   dozySystemRestClient (RestClient.Builder)
└─ WebFlux
   ├─ DozyAuthReactiveAutoConfiguration
   ├─ DozyReactiveJwtDecoders        서명 검증을 별도 스케줄러에서 실행 (공개)
   ├─ DozyReactiveAuth               dozyAuth SpEL 헬퍼 (Mono<Boolean>)
   ├─ DozyReactiveProblemResponses   401·403 응답 쓰기 (서비스의 서버 codec)
   └─ DozySystemClientReactiveAutoConfiguration  dozySystemWebClient (WebClient.Builder)
src/main/resources/META-INF/
├─ spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
├─ spring/…WebFluxTest.imports, …WebMvcTest.imports   슬라이스 테스트에서도 자동 설정이 켜지도록 등록
└─ spring-configuration-metadata.json   설정 자동완성 (직접 작성, 테스트로 설정 클래스와 비교)
```

- `DozyJwtDecoders`, `DozyReactiveJwtDecoders`는 공개 API입니다. 자동 설정과 다른 키 출처(auth-test의 테스트 키 등)로 같은 검증 규칙의 디코더를 만들 때 씁니다. `aud`를 검사하지 않는 `createWithoutAudienceCheck`는 Auth 서버용이며 서비스는 쓰지 않습니다 ([starter.md §3](../docs/starter.md#3-제공하는-빈)).

- Spring MVC 전용 클래스와 WebFlux 전용 클래스는 파일을 나눕니다. 서비스에는 둘 중 한쪽 라이브러리(servlet API 또는 Reactor)만 있을 수 있기 때문입니다.
- 서비스 간 호출(`dozy.auth.client.*`)은 [starter.md §6](../docs/starter.md#6-서비스-간-호출)에 있습니다. `spring-security-oauth2-client`는 `implementation`이라 서비스의 컴파일 classpath에 실리지 않고, OAuth2 Client 객체는 빈으로 등록하지 않습니다.

### 필터 체인을 바꾸고 싶을 때

체인만 새로 정의하고 스타터의 부품 빈을 주입받아 쓰면 토큰 검증은 그대로 유지됩니다. stateless, CSRF, 공개 경로는 직접 다시 설정합니다. WebFlux 예시입니다 (Spring MVC는 `SecurityFilterChain`, `HttpSecurity`, `AuthenticationEntryPoint`, `AccessDeniedHandler`로 같은 구성).

```kotlin
@Bean
fun securityWebFilterChain(
    http: ServerHttpSecurity,
    dozyJwtAuthenticationConverter: Converter<Jwt, Mono<AbstractAuthenticationToken>>,
    entryPoint: ServerAuthenticationEntryPoint,
    accessDeniedHandler: ServerAccessDeniedHandler,
): SecurityWebFilterChain =
    http {
        authorizeExchange {
            authorize("/webhooks/**", permitAll)
            authorize(anyExchange, authenticated)
        }
        oauth2ResourceServer {
            jwt { jwtAuthenticationConverter = dozyJwtAuthenticationConverter } // ReactiveJwtDecoder는 스타터 빈을 자동으로 씀
            authenticationEntryPoint = entryPoint
        }
        exceptionHandling {
            authenticationEntryPoint = entryPoint
            this.accessDeniedHandler = accessDeniedHandler
        }
        securityContextRepository = NoOpServerSecurityContextRepository.getInstance()
        csrf { disable() }
    }
```

## 제약

- `auth-core`만 의존합니다. `auth-server` 코드를 참조하지 않습니다.
- JVM 17 타깃, `explicitApi()`입니다.
- Spring Boot BOM을 배포 메타데이터에 싣지 않습니다. 서비스의 Spring 버전을 바꾸지 않기 위해서입니다 (`build-logic`의 `dozy.spring-library`).
- 모든 빈은 서비스가 교체할 수 있게 `@ConditionalOnMissingBean`으로 등록합니다.
- **공개 API나 동작을 바꾸면 라이브러리 버전에 반영하고, Notion 연동 가이드도 갱신합니다.**

## 테스트

```bash
./gradlew :auth-spring-boot-starter:test
```

- `DozyJwtDecodersTest`: 검증 실패 경우(`alg`, `typ`, 서명, `kid`, 만료, `iss`, `aud`, realm과 principal type 불일치 등)마다 거부, JWKS 재조회와 재조회 제한. Spring MVC용(`Servlet`)과 WebFlux용(`Reactive`) 디코더에 같은 테스트를 돌리고, `aud`를 검사하지 않는 디코더(`ServletWithoutAudienceCheck`, `ReactiveWithoutAudienceCheck`)에는 `aud`를 뺀 같은 거부 테스트와 `aud` 통과 테스트를 돌립니다.
- `DozyAuthServletWebTest`, `DozyAuthReactiveWebTest`: 샘플 앱(`sample`, `reactivesample`)으로 401·403 응답 형식(필드 목록까지), 서비스의 Jackson 설정 적용, 서비스 trace 필터가 붙인 `X-Trace-Id` 유지, 서비스가 정의한 401·403 핸들러로 교체, `@PreAuthorize`, `@CurrentPrincipal`, `dozyAuth`. WebFlux는 `suspend` 컨트롤러와 `Mono` 컨트롤러를 모두 확인합니다.
- `DozyServletProblemWriterTest`, `DozyReactiveProblemWriterTest`: Jackson 3·Jackson 2 변환기(codec)로 쓴 본문과 변환기가 없을 때 직접 쓴 본문이 같은 필드와 값인지
- `DozyTraceIdsTest`: trace id를 정하는 순서
- `DozyAuthServletAutoConfigurationTest`, `DozyAuthReactiveAutoConfigurationTest`: 필수 설정 누락 시 기동 실패, 앱 종류에 맞는 빈만 등록, 서비스가 빈을 정의하면 스타터 빈이 빠짐
- `DozySystemClientTest`: system token 클라이언트. Spring MVC(`Servlet`)와 WebFlux(`Reactive`)에 같은 테스트를 돌립니다. 기본은 꺼짐, 필수 설정, Bearer 첨부, 발급 요청 형식(`client_secret_basic`, form), 캐시 재사용과 만료 전 재발급(테스트 시계), `invalid_client`·연결 실패, 로그에 secret 없음, 서비스의 기본 builder·OAuth2 Client 설정과 함께 쓰기
- JWKS와 토큰 엔드포인트는 테스트 안에서 JDK `HttpServer`로 띄웁니다 (`support/JwksServer`, `support/AuthTokenServer`).
