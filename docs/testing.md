# 테스트

테스트를 어떻게 나누고, 이름과 기대값을 어떻게 쓰는지 정합니다. 실행 명령은 각 모듈 README에 있습니다.

## 1. 종류와 도구

| 종류 | 대상 | 도구 |
|---|---|---|
| 단위 | `domain`, `application/service`, auth-core | JUnit, MockK |
| 웹 | 컨트롤러, 에러 응답, 보안 설정 | `@WebMvcTest`, spring-security-test, springmockk |
| 영속성 | `adapter/outbound/persistence` | Testcontainers PostgreSQL (실제 SQL 검증) |
| 통합 | 여러 계층을 거치는 흐름 (예: 발급한 토큰을 JWKS API의 공개키로 검증) | `@SpringBootTest`, MockMvc, Testcontainers |
| 아키텍처 | 계층 의존, 이름 규칙 ([architecture.md §6.3](architecture.md#63-아키텍처-테스트)) | Konsist |

- 테스트 대역은 MockK를 씁니다. 스프링 빈을 대체할 때는 springmockk를 씁니다.
- 스프링 컨텍스트를 띄우는 테스트는 `test` 프로필을 씁니다 ([configuration.md §4](configuration.md#4-프로필)).

## 2. 이름

테스트 이름만 읽어도 무엇을 확인하는지 알 수 있어야 합니다.

| 규칙 | 좋은 예 | 나쁜 예 |
|---|---|---|
| 확인하는 동작을 문장으로 씁니다 | `서명 키가 최소 크기보다 작으면 기동 실패` | `서명 키 테스트` |
| 명세의 위치(절 번호, ADR 번호)는 쓰지 않습니다. 필요하면 클래스 주석에 씁니다 | `토큰에 주체, audience, role을 담음` | `claim은 token 명세 3장의 형식` |
| 값을 넣지 않습니다. 값이 바뀌면 이름만 틀린 채로 남습니다 | `exp는 iat에서 access token 수명만큼 뒤` | `exp는 iat에서 10분 뒤` |
| [domain.md](domain.md)의 규칙을 확인하면 규칙 ID를 앞에 붙입니다 | `DOM-04 파트너에게 role이 있으면 거부` | `파트너 role 거부` |

- 값 자체를 고정하는 테스트(§3의 정책 값 테스트)는 값이 곧 확인하는 내용이므로 이름에 값을 씁니다. 예: `access token은 10분 동안 유효`
- 규칙 ID로 명세의 규칙과 테스트를 서로 찾을 수 있습니다 (`grep SES-03`).

## 3. 기대값

기대값은 값이 어디서 왔는지에 따라 다르게 씁니다.

| 값 | 쓰는 법 | 예 |
|---|---|---|
| 테스트에 넣은 입력 | fixture | `"${ISSUER_BASE.value}/realms/internal"`의 앞부분, `"employee:${EMPLOYEE.id}"`의 id |
| 정책 값 ([domain.md §2](domain.md#2-정책-값)) | `AuthPolicy` | `AuthPolicy.ACCESS_TOKEN_TTL` |
| 서비스·앱과의 계약 (claim 이름, 값 형식, header, 경로 형식) | 명세의 문자열 그대로 | `"principalType"`, `"at+jwt"`, `"/realms/internal"` |

- **구현 코드로 기대값을 만들지 않습니다.** `Realm.issuer()`, `ClaimNames`, `AccessTokenFormat` 같은 구현을 기대값에 쓰면, 구현이 틀려도 테스트가 통과합니다.
- 정책 값 자체는 `AuthPolicyTest` 한 곳에서만 숫자로 고정합니다. 정책을 바꿀 때는 domain.md §2와 이 테스트만 고칩니다.

```kotlin
assertEquals("${ISSUER_BASE.value}/realms/internal", claims.issuer)   // 입력은 fixture, 형식은 명세
assertEquals(Realm.INTERNAL.issuer(ISSUER_BASE.value), claims.issuer) // 구현을 구현과 비교 (쓰지 않음)
```

## 4. fixture

- 여러 테스트가 함께 쓰는 입력 데이터는 `src/test/kotlin/.../support/`에 둡니다.
- 이름으로 역할을 드러냅니다. 예: `EMPLOYEE`, `PARTNER`, `CURRENT_KID`(서명 키), `NEXT_KID`(교체 준비 중인 키)
- 여러 값을 채워야 하는 입력은 기본값이 있는 만들기 함수로 두고, 테스트에서는 검사할 값만 바꿔 넘깁니다. 예: `accessTokenClaims(principal = SYSTEM, sessionId = null)`
- fixture에는 입력만 둡니다. 기대값은 §3에 따라 각 테스트에 씁니다.
- 한 테스트에서만 쓰는 입력(예: 주소 정규화 테스트의 `"https://auth.dozycoffee.com/"`)은 그 테스트에 둡니다.

## 5. 시간과 무거운 데이터

- 현재 시각은 `Clock`을 고정해서 넘깁니다 (`TokenFixtures.FIXED_CLOCK`). `Instant.now()`는 쓰지 않습니다.
- 만들기 비싼 데이터(RSA 서명 키 등)는 테스트 JVM 전체에서 한 번만 만듭니다 (`TestSigningKeys`).
- 파일을 쓰는 테스트는 JUnit `@TempDir`을 씁니다.

## 6. 규칙을 검사하는 테스트

Konsist처럼 규칙 위반을 잡는 테스트는 통과하는 것만으로는 동작을 믿을 수 없습니다.

- 규칙마다 일부러 어긴 코드를 넣어 테스트가 실패하는지 확인하고, 올바른 예시 코드로 통과하는지도 확인합니다.
- 확인에 쓴 코드는 커밋하지 않고 지웁니다.
- 확인할 때는 ktlint를 포함한 전체 빌드를 돌립니다. 테스트만 돌리면 패키지 이름 같은 다른 규칙 위반을 놓칩니다.
