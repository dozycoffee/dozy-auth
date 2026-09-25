# auth-core

Auth 서버(발급)와 서비스 라이브러리(검증)가 함께 쓰는 타입과 상수입니다. 두 쪽이 같은 정의를 쓰게 해서 claim 이름이나 형식이 어긋나지 않게 합니다.

## 명세

- [docs/token.md §10 공유 타입](../docs/token.md#10-공유-타입-auth-core): 타입 정의의 기준
- [docs/token.md §3 Claims](../docs/token.md#3-claims): claim 이름과 형식
- [docs/domain.md §1 용어](../docs/domain.md#1-용어): realm, principal type, role 코드 규칙

## 사용 방법

서비스는 보통 직접 추가하지 않습니다. `auth-spring-boot-starter`가 `api`로 포함합니다.

```kotlin
dependencies {
    implementation("com.dozycoffee.auth:auth-core:{version}")
}
```

저장소 설정은 [auth-spring-boot-starter README](../auth-spring-boot-starter/README.md#사용-방법)와 같습니다.

## 공개 타입 (준비 중)

| 타입 | 설명 |
|---|---|
| `Realm` | `INTERNAL`, `PARTNER`, `CUSTOMER` |
| `PrincipalType` | `EMPLOYEE`, `SYSTEM`, `PARTNER`, `CUSTOMER` |
| `PrincipalKey` | `(type, id)`. `sub` 문자열 생성과 파싱 |
| `AuthenticatedPrincipal` | 검증된 토큰의 주체 (key, realm, 자기 audience의 role, 세션 id) |
| `ClaimNames` | claim 이름 상수 |
| role 코드 검증 | `{audience}:{code}` 파싱과 형식 검사 |

정의와 동작은 명세를 따르며, 이 표에는 이름과 한 줄 설명만 둡니다.

## 구조

```text
src/main/kotlin/com/dozycoffee/auth/core/
```

(준비 중: 타입이 추가되면 패키지 구성을 적습니다.)

## 제약

- **Spring을 포함한 외부 라이브러리에 의존하지 않습니다.** Kotlin 표준 라이브러리만 씁니다.
- JVM 17 타깃, `explicitApi()`입니다. 공개 선언에는 가시성과 반환 타입을 명시하고 KDoc을 씁니다.
- 이 모듈을 바꾸면 모든 서비스에 영향이 갑니다. 공개 API 변경은 라이브러리 버전에 반영하고, 토큰 계약이 바뀌면 major 버전입니다([token.md §11](../docs/token.md#11-호환성)).

## 테스트

```bash
./gradlew :auth-core:test
```

- 순수 단위 테스트만 있습니다. `sub` 변환 왕복, 잘못된 형식 거부 같은 경계값을 검사합니다.
