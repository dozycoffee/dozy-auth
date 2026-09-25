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

| 도구 | 쓰는 곳 |
|---|---|
| `@WithDozyPrincipal` | MockMvc 컨트롤러 테스트. JWT 없이 인증된 사용자를 바로 넣습니다 |
| `DozyTestTokens` | 통합 테스트. 실제 검증 과정을 거치는 서명된 토큰을 만듭니다 |

(준비 중: 두 도구가 구현되면 짧은 예시를 추가합니다.)

## 구조 (준비 중)

```text
src/main/kotlin/com/dozycoffee/auth/test/
```

## 제약

- `auth-core`, `auth-spring-boot-starter`에 의존합니다.
- JVM 17 타깃, `explicitApi()`입니다.
- 테스트용 서명 키는 이 모듈 안에서만 씁니다. 운영 키나 로컬 키와 섞지 않습니다.
- 서비스의 `testImplementation`으로만 쓰이도록 안내합니다. 운영 classpath에 들어가면 테스트 키로 서명한 토큰이 통과할 수 있습니다.

## 테스트

```bash
./gradlew :auth-test:test
```

- 스타터와 같은 규칙으로 role이 변환되는지, 발급한 토큰이 스타터 검증을 통과하는지 확인합니다.
