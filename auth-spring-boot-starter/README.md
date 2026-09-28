# auth-spring-boot-starter

서비스(WMS, Catalog, Store)가 Auth 토큰을 검증하고 role로 인가할 수 있게 하는 Spring Boot 자동 설정입니다.

> 서비스에서 쓰는 방법은 Notion "서비스 연동 가이드"에 자세히 있습니다. 이 README는 설치와 최소 설정만 다룹니다.

## 명세

- [docs/starter.md](../docs/starter.md): 설정 키, 제공 빈, 인가 도구, 에러 응답, 서비스 간 호출. **이 모듈의 기준**
- [docs/token.md §6](../docs/token.md#6-검증-규칙): 토큰 검증 규칙
- [docs/api/conventions.md §4](../docs/api/conventions.md#4-에러-응답): 401·403 응답 형식

## 사용 방법

(준비 중: GitHub Packages 배포 설정)

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

## 구조

```text
src/main/kotlin/com/dozycoffee/auth/starter/
├─ DozyAuthAutoConfiguration      빈 등록 (모두 @ConditionalOnMissingBean)
├─ DozyAuthProperties             dozy.auth.* 설정과 필수 값 검사
├─ DozyJwtDecoders                JwtDecoder: RS256, JWKS 캐시·재조회 제한
├─ DozyTokenValidators            token.md §6의 3, 6~9 검증기
├─ DozyJwtAuthenticationConverter role 변환, AuthenticatedPrincipal 생성
├─ DozyAuthenticationToken        인증 결과 (principal = AuthenticatedPrincipal)
├─ CurrentPrincipal, DozyAuth     컨트롤러 인자, SpEL 헬퍼
└─ DozyProblemResponses           401·403 Problem Details, traceId
src/main/resources/META-INF/
├─ spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└─ spring-configuration-metadata.json   설정 자동완성 (직접 작성, 테스트로 설정 클래스와 비교)
```

- 서비스 간 호출(`dozy.auth.client.*`, [starter.md §6](../docs/starter.md#6-서비스-간-호출))은 준비 중입니다.

### SecurityFilterChain을 바꾸고 싶을 때

체인만 새로 정의하고 스타터의 부품 빈을 주입받아 쓰면 토큰 검증은 그대로 유지됩니다. stateless, CSRF, 공개 경로는 직접 다시 설정합니다.

```kotlin
@Bean
fun securityFilterChain(
    http: HttpSecurity,
    dozyJwtAuthenticationConverter: Converter<Jwt, AbstractAuthenticationToken>,
    entryPoint: AuthenticationEntryPoint,
    accessDeniedHandler: AccessDeniedHandler,
): SecurityFilterChain {
    http {
        authorizeHttpRequests {
            authorize("/webhooks/**", permitAll)
            authorize(anyRequest, authenticated)
        }
        oauth2ResourceServer {
            jwt { jwtAuthenticationConverter = dozyJwtAuthenticationConverter } // JwtDecoder는 스타터 빈을 자동으로 씀
            authenticationEntryPoint = entryPoint
        }
        exceptionHandling { this.accessDeniedHandler = accessDeniedHandler }
        sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        csrf { disable() }
    }
    return http.build()
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

- `DozyJwtDecodersTest`: 검증 실패 경우(`alg`, `typ`, 서명, `kid`, 만료, `iss`, `aud`, realm과 principal type 불일치 등)마다 거부, JWKS 재조회와 재조회 제한
- `DozyAuthWebTest`: 샘플 앱(`src/test/.../sample`)으로 401·403 응답 형식, `@PreAuthorize`, `@CurrentPrincipal`, `dozyAuth`
- `DozyAuthAutoConfigurationTest`: 필수 설정 누락 시 기동 실패, 서비스가 빈을 정의하면 스타터 빈이 빠짐
- JWKS는 테스트 안에서 JDK `HttpServer`로 띄웁니다 (`support/JwksServer`).
