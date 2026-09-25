# 토큰

Auth가 발급하는 토큰의 형식과 검증 규칙입니다. Auth(발급)와 모든 서비스(검증) 사이의 계약이며, 버전은 **v1.0**입니다.

- 수명 값은 [domain.md §2](domain.md#2-정책-값)를 따릅니다.
- refresh 쿠키의 이름과 속성은 [api/conventions.md §6](api/conventions.md#6-refresh-쿠키)을 따릅니다.
- 이 문서가 바뀌면 Notion 서비스 연동 가이드도 갱신합니다.

## 1. 종류

| 토큰 | 형식 | 발급 대상 | 용도 | 수명 |
|---|---|---|---|---|
| access token | JWT (RS256) | employee, partner, customer | 서비스 API 호출 | `policy.access-token-ttl` |
| system token | JWT (RS256) | system | 서비스 간 API 호출 | `policy.access-token-ttl` |
| refresh token | opaque 문자열 | employee, partner, customer | Auth에서 access token 재발급 | `policy.refresh-idle-ttl`, 최대 `policy.refresh-absolute-ttl` |

- system token은 access token과 형식이 같고 `principalType`이 `system`인 점만 다릅니다. 이하 "access token"은 둘 다를 뜻합니다.
- refresh token은 Auth만 해석합니다. 서비스는 받지도 검증하지도 않습니다.

## 2. Header

```json
{ "alg": "RS256", "typ": "at+jwt", "kid": "dozy-2026-09" }
```

| 필드 | 값 |
|---|---|
| `alg` | `RS256` 고정 |
| `typ` | `at+jwt` 고정 (RFC 9068) |
| `kid` | 서명 키 식별자. 형식은 `dozy-{연도}-{월}` |

## 3. Claims

| claim | 필수 | 타입 | 설명 |
|---|---|---|---|
| `iss` | ✅ | string | `{issuer-base}/realms/{realm}` (예: `https://auth.dozycoffee.com/realms/internal`) |
| `sub` | ✅ | string | `{principalType}:{principalId}` |
| `aud` | ✅ | string[] | 이 토큰을 받을 수 있는 audience ([§4](#4-aud-결정-규칙)) |
| `principalType` | ✅ | string | `employee` / `system` / `partner` / `customer` |
| `principalId` | ✅ | string | principal id. 소문자·하이픈 포함 정규형 UUID (예: `0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f`) ([ADR-0028](adr/0028-uuidv7-principal-id.md)) |
| `roles` | ✅ | string[] | `{audience}:{code}` 목록. 없으면 빈 배열 |
| `iat` | ✅ | number | 발급 시각 (Unix 초) |
| `exp` | ✅ | number | 만료 시각 (Unix 초) |
| `jti` | ✅ | string | 토큰 고유 ID (UUID) |
| `sid` | | string | refresh 세션 id (UUID). system token에는 없음 |

- `sub`, `principalType`, `principalId`는 항상 일치합니다. 예: `sub`가 `employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f`이면 `principalType`은 `employee`, `principalId`는 `0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f`입니다.
- 받는 쪽은 `principalId`의 UUID 형식(8-4-4-4-12자리 소문자 16진수)만 검사하고 버전은 검사하지 않습니다. Auth는 UUIDv7로 발급합니다.
- `iss`의 realm과 `principalType`은 [DOM-01](domain.md#11-realm과-principal-type)의 조합만 허용합니다.
- `roles`에는 `aud`에 포함된 audience의 role만 들어갑니다.
- 개인정보(이름, 이메일)는 넣지 않습니다 ([SEC-04](domain.md#12-민감정보-sec)).
- `sid`는 추후 세션 단위 즉시 차단에 쓸 예정이며, 지금은 참고용입니다.

## 4. aud 결정 규칙

| principal type | `aud` |
|---|---|
| `employee`, `system` | 보유한 role의 audience 목록 (중복 제거) |
| `partner` | `["store"]` 고정 |
| `customer` | 추후 정의 |

- role이 없는 employee는 `aud`가 빈 배열이며, 어떤 서비스도 호출할 수 없습니다. Auth 자신의 `/realms/**` API는 `aud`를 검사하지 않으므로 내 정보 조회 등은 가능합니다 ([api/conventions.md §2](api/conventions.md#2-인증-방식)).
- 토큰은 발급 시점의 role로 만들어집니다. role 변경은 다음 갱신 때 반영됩니다 ([SES-05](domain.md#6-세션-규칙-ses)).

## 5. 예시

**직원**

```json
{
  "iss": "https://auth.dozycoffee.com/realms/internal",
  "sub": "employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
  "aud": ["wms", "catalog"],
  "principalType": "employee",
  "principalId": "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
  "roles": ["wms:inbound_manager", "wms:stock_viewer", "catalog:menu_editor"],
  "iat": 1790000000,
  "exp": 1790000600,
  "jti": "5f2b9c1e-8a4d-4c1e-9d3f-2b7a6e0c1d4f",
  "sid": "8c1d4f5f-2b9c-4e8a-a4d1-c9d3f2b7a6e0"
}
```

**점주**

```json
{
  "iss": "https://auth.dozycoffee.com/realms/partner",
  "sub": "partner:0199a3c5-1d4f-7a8b-b2c6-5e9f0a3d7c21",
  "aud": ["store"],
  "principalType": "partner",
  "principalId": "0199a3c5-1d4f-7a8b-b2c6-5e9f0a3d7c21",
  "roles": [],
  "iat": 1790000000,
  "exp": 1790000600,
  "jti": "a4d1c9d3-f2b7-4a6e-8c1d-4f5f2b9c1e8a",
  "sid": "2b9c1e8a-4d1c-4d3f-b7a6-e0c1d4f5f2b9"
}
```

**서비스 (Store → Auth 내부 API)**

```json
{
  "iss": "https://auth.dozycoffee.com/realms/internal",
  "sub": "system:0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
  "aud": ["auth"],
  "principalType": "system",
  "principalId": "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
  "roles": ["auth:partner_reader"],
  "iat": 1790000000,
  "exp": 1790000600,
  "jti": "c9d3f2b7-a6e0-4c1d-8f5f-2b9c1e8a4d1c"
}
```

## 6. 검증 규칙

토큰을 받는 쪽(서비스, 그리고 Auth의 관리·내부 API)은 아래 순서로 검증합니다. 하나라도 실패하면 `401`입니다. 스타터가 1~9를 처리합니다.

| 순서 | 검증 | 실패 조건 |
|---|---|---|
| 1 | `Authorization: Bearer {token}` | 헤더 없음, 형식 오류 |
| 2 | `alg` | `RS256`이 아님 (`none`, `HS*` 등 거부) |
| 3 | `typ` | `at+jwt`가 아님 |
| 4 | `kid`로 JWKS에서 키 조회 | 재조회 후에도 키 없음 |
| 5 | 서명 | 불일치 |
| 6 | `exp`, `iat` | 만료. `policy.clock-skew` 허용 |
| 7 | `iss` | 허용한 realm의 issuer가 아님 |
| 8 | `aud` | 자기 audience가 없음 |
| 9 | `principalType`, `principalId`, `sub` | [DOM-01](domain.md#11-realm과-principal-type) 조합 위반, `principalId`가 UUID 형식이 아님, `sub` 불일치, 값 누락 |

검증을 통과한 뒤의 판단은 `403`입니다.

| 판단 | 근거 | 담당 |
|---|---|---|
| 기능 인가 | 자기 audience의 role | 각 서비스 |
| 리소스 인가 | 서비스 자체 데이터 (예: `store_member`) | 각 서비스. 모든 서비스의 필수 규칙 (id를 추측하기 어렵다는 것은 인가를 대신하지 않음) |

- 서비스는 모르는 claim을 무시해야 합니다.

**서비스별 허용 realm**

| 받는 쪽 | 허용 realm |
|---|---|
| WMS, Catalog | `internal` |
| Store | `internal`, `partner` |
| Auth 관리·내부 API | `internal` |

## 7. JWKS와 서명 키

- 주소: `{issuer-base}/.well-known/jwks.json`. 모든 realm이 같은 키셋을 씁니다. realm 구분은 `iss` 검증으로 합니다.
- 키: `policy.signing-key-size`. 서명은 활성 키 하나로만 하고, JWKS에는 게시 중인 키를 모두 싣습니다.
- 서비스 쪽: JWKS를 캐시하고, 모르는 `kid`가 오면 한 번 재조회합니다. 재조회는 최소 간격을 둡니다.
- 교체 순서: ① 새 키를 JWKS에 먼저 게시 → ② 서비스 캐시가 갱신될 때까지 대기 → ③ 새 키로 서명 시작 → ④ 기존 키로 서명된 토큰이 모두 만료될 때까지 대기 → ⑤ 기존 키 제거. 키 설정 방식은 [configuration.md §3](configuration.md#3-서명-키)을 따릅니다.

## 8. system token 발급

서비스는 client credentials로 발급받습니다. 형식은 OAuth 2.0 표준이며, 상세는 [api/internal.md](api/internal.md#서비스-토큰-발급)에 있습니다.

- `aud`와 `roles`는 system client에 부여된 role로 정해집니다.
- 만료 직전까지 캐시해서 재사용합니다.

## 9. 클라이언트(앱) 규칙

- access token은 메모리에만 보관합니다. localStorage, sessionStorage, 일반 쿠키에 저장하지 않습니다.
- access token을 파싱해서 화면에 쓰지 않습니다. 표시 정보는 `GET /realms/{realm}/me`로 조회합니다. 토큰 형식은 서비스를 위한 계약입니다.
- 만료 판단은 로그인·갱신 응답의 `expiresIn`으로 하고, 만료 1분 전쯤 갱신합니다. 갱신은 동시에 한 번만 합니다.
- 갱신이 `409 TOKEN_ROTATED`이면 한 번 재시도하고, `401`이면 로그인 화면으로 보냅니다. 네트워크 오류나 `5xx`는 재시도합니다.

## 10. 공유 타입 (auth-core)

발급 측과 검증 측이 같은 타입을 쓰도록 `auth-core`에 둡니다. Spring 의존이 없습니다.

```kotlin
enum class Realm { INTERNAL, PARTNER, CUSTOMER }
enum class PrincipalType { EMPLOYEE, SYSTEM, PARTNER, CUSTOMER }

data class PrincipalKey(val type: PrincipalType, val id: UUID) {
    val sub: String get() = "${type.name.lowercase()}:$id"
}

data class AuthenticatedPrincipal(
    val key: PrincipalKey,
    val realm: Realm,
    val roles: Set<String>,   // 자기 audience의 role, prefix 제거 (예: "inbound_manager")
    val sessionId: String?,   // sid. system token은 null
)
```

- claim 이름 상수(`ClaimNames`), `sub` 파싱, role 코드 형식 검증([DOM-03](domain.md#11-realm과-principal-type))도 `auth-core`에 둡니다.
- JSON의 enum 값은 소문자(`employee`), Kotlin enum은 대문자입니다. 변환은 `auth-core`가 담당합니다.

## 11. 호환성

| 변경 | 예시 | 버전 |
|---|---|---|
| 선택 claim 추가 | 참고용 claim 추가 | minor (v1.1) |
| 수명 변경 | access token 수명 단축 | minor, 사전 공지 |
| 필수 claim 추가·삭제·이름 변경 | `principalId` → `pid` | **major** |
| claim 의미·형식 변경 | `sub` 형식, role 형식 | **major** |
| 서명 알고리즘 변경 | RS256 → ES256 | **major** |

major 변경은 스타터의 major 버전과 함께 배포하고, 전환 기간 동안 두 형식을 모두 받게 합니다.
