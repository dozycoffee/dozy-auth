# auth-test

서비스가 Auth 서버 없이 인증된 상태로 테스트할 수 있게 하는 도구입니다.

## 명세

- [docs/starter.md §7](../docs/starter.md#7-auth-test): 애노테이션 속성, 토큰 발급 유틸. **이 모듈의 기준**
- [docs/token.md](../docs/token.md): 테스트 토큰도 같은 형식을 따릅니다

## 사용 방법

저장소 설정은 [auth-spring-boot-starter README](../auth-spring-boot-starter/README.md#사용-방법)와 같습니다.

```kotlin
dependencies {
    testImplementation("com.dozycoffee.auth:auth-test:{version}")
}
```

Spring MVC(MockMvc)와 WebFlux(`WebTestClient`)를 모두 지원합니다. 아래는 WebFlux 예시입니다.

**컨트롤러 테스트: `@WithDozyPrincipal`** (토큰 검증 없이 인증된 사용자를 바로 넣음)

```kotlin
@WebFluxTest(InboundController::class)
class InboundControllerTest(@Autowired val client: WebTestClient) {
    @Test
    @WithDozyPrincipal(roles = ["wms:inbound_manager"])
    fun `입고 관리자는 입고를 등록할 수 있다`() {
        client.post().uri("/inbounds").bodyValue(request).exchange().expectStatus().isOk
    }
}
```

**통합 테스트: `DozyTestTokens`** (스타터의 실제 검증 체인을 거치는 서명된 토큰)

```kotlin
@SpringBootTest
@AutoConfigureWebTestClient
class InboundApiTest(@Autowired val client: WebTestClient, @Autowired val tokens: DozyTestTokens) {
    @Test
    fun `파트너 토큰으로는 호출할 수 없다`() {
        client.get().uri("/inbounds")
            .header("Authorization", "Bearer ${tokens.issue(type = PrincipalType.PARTNER)}")
            .exchange().expectStatus().isUnauthorized
    }
}
```

속성과 인자는 [starter.md §7](../docs/starter.md#7-auth-test)에 있습니다.

## 구조

```text
src/main/kotlin/com/dozycoffee/auth/test/
├─ WithDozyPrincipal                          애노테이션
├─ WithDozyPrincipalSecurityContextFactory    claim만 채운 JWT를 스타터 변환기에 넣어 인증 정보 생성
├─ DozyTestTokens                             서명된 테스트 토큰 (빈)
├─ TestSigningKeys                            테스트 키 (JVM당 한 번 생성, 메모리에만)
└─ DozyTestAutoConfiguration                  스타터 디코더가 테스트 키를 믿게 함, DozyTestTokens 빈
src/main/resources/META-INF/spring/
├─ org.springframework.boot.autoconfigure.AutoConfiguration.imports
├─ org.springframework.boot.webflux.test.autoconfigure.WebFluxTest.imports    @WebFluxTest에서도 켜짐
└─ org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest.imports      @WebMvcTest에서도 켜짐
```

## 제약

- `auth-core`, `auth-spring-boot-starter`에 의존합니다.
- JVM 17 타깃, `explicitApi()`입니다.
- 테스트용 서명 키는 이 모듈 안에서만 씁니다. 운영 키나 로컬 키와 섞지 않습니다.
- 서비스의 `testImplementation`으로만 쓰이도록 안내합니다. 운영 classpath에 들어가면 스타터가 Auth의 공개키 대신 테스트 키를 믿게 되어, 실제 토큰이 모두 거부됩니다.

## 테스트

```bash
./gradlew :auth-test:test
```

- 샘플 앱(`reactivesample`, `servletsample`)으로 두 도구를 확인합니다. WebFlux는 슬라이스(`@WebFluxTest`)와 통합(`@SpringBootTest`) 테스트, Spring MVC는 대표 경우만 둡니다.
- `SamePrincipalTest`: 같은 type·id·role이면 애노테이션과 테스트 토큰이 같은 principal과 권한을 만드는지 확인합니다.
