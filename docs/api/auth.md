# 인증 API

로그인 세션을 다루는 API입니다. 공통 규칙은 [conventions.md](conventions.md)를 따릅니다.

## 흐름

### 로그인

```mermaid
sequenceDiagram
    participant App as 앱
    participant Auth as Auth
    participant DB as Auth DB

    App->>Auth: POST /realms/{realm}/login
    Auth->>Auth: 요청 제한, 계정 잠금 확인
    Auth->>DB: realm에 맞는 profile에서 이메일로 조회
    Auth->>Auth: 비밀번호 검증, 계정 상태 확인
    Auth->>DB: refresh 세션 생성
    Auth-->>App: accessToken + dozy_refresh 쿠키
    App->>Auth: GET /realms/{realm}/me
    Auth-->>App: 표시 정보
```

검증 순서는 [LGN-01](../domain.md#5-로그인-규칙-lgn)을 따릅니다.

### 토큰 갱신

```mermaid
sequenceDiagram
    participant App as 앱
    participant Auth as Auth
    participant DB as Auth DB

    App->>Auth: POST /realms/{realm}/token/refresh (쿠키 자동 전송)
    Auth->>DB: 쿠키 값의 해시로 세션 조회
    alt 현재 토큰
        Auth->>DB: 해시 교체, 만료 연장
        Auth-->>App: 새 accessToken + 새 쿠키
    else 직전 토큰, 유예 시간 이내
        Auth-->>App: 409 TOKEN_ROTATED
        App->>Auth: 한 번 재시도
    else 직전 토큰, 유예 시간 초과
        Auth->>DB: 세션 폐기 (REUSE_DETECTED)
        Auth-->>App: 401 SESSION_REVOKED
    else 없음, 만료, 폐기
        Auth-->>App: 401 SESSION_EXPIRED
    end
```

판정 규칙은 [SES-03](../domain.md#6-세션-규칙-ses)을 따릅니다.

## 엔드포인트

### 로그인

`POST /realms/{realm}/login`

| 인증 | 필요 role | realm |
|---|---|---|
| 없음 | - | `internal`, `partner` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `email` | string | ✅ | 로그인 이메일 |
| `password` | string | ✅ | 비밀번호 |

**응답** `200 OK`

```http
Set-Cookie: dozy_refresh=...; HttpOnly; Secure; SameSite=Strict; Path=/realms/{realm}; Max-Age=...
```

```json
{ "accessToken": "eyJhbGciOiJSUzI1NiIs...", "tokenType": "Bearer", "expiresIn": 600 }
```

- `expiresIn`은 초 단위이며 `policy.access-token-ttl`입니다.
- 토큰을 담은 응답이라 `Cache-Control: no-store`입니다.

**에러**

| code | 조건 |
|---|---|
| `INVALID_CREDENTIALS` | 계정 없음, 비밀번호 불일치, 비활성화된 계정 |
| `EMAIL_NOT_VERIFIED` | 이메일 인증 전 파트너 |
| `ACCOUNT_SUSPENDED` | 정지된 계정 |
| `TOO_MANY_ATTEMPTS` | IP 요청 제한 또는 계정 잠금 |

**규칙** [LGN-01](../domain.md#5-로그인-규칙-lgn)~[LGN-03](../domain.md#5-로그인-규칙-lgn), [SES-01](../domain.md#6-세션-규칙-ses)

- `user_agent`, `ip`를 세션에 기록합니다.
- 감사 로그: `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, 잠금이 걸리면 `ACCOUNT_LOCKED`

### 토큰 갱신

`POST /realms/{realm}/token/refresh`

| 인증 | 필요 role | realm |
|---|---|---|
| refresh 쿠키 | - | `internal`, `partner` |

**요청** 본문 없음

**응답** `200 OK`. 새 `dozy_refresh` 쿠키와 로그인과 같은 본문

**에러**

| code | 조건 |
|---|---|
| `TOKEN_ROTATED` | 교체 후 `policy.rotation-grace` 이내의 직전 토큰. 앱은 한 번 재시도 |
| `SESSION_EXPIRED` | 세션 없음·만료·폐기, 계정이 `ACTIVE`가 아님 |
| `SESSION_REVOKED` | 재사용 탐지로 방금 폐기함 |
| `FORBIDDEN` | 허용되지 않은 `Origin` |

**규칙** [SES-03](../domain.md#6-세션-규칙-ses)~[SES-05](../domain.md#6-세션-규칙-ses)

- 쿠키의 realm(경로)과 세션의 realm이 다르면 `SESSION_EXPIRED`입니다.
- 쿠키가 없어도 `SESSION_EXPIRED`입니다.
- 에러 응답(`401`, `409`)은 쿠키를 설정하거나 삭제하지 않습니다. `TOKEN_ROTATED`는 세션이 살아 있고, 나머지는 서버에서 이미 쓸 수 없는 쿠키입니다.
- `SESSION_REVOKED`로 끝나도 세션 폐기와 감사 기록은 반영됩니다.
- 감사 로그: 재사용 탐지 시 `SESSION_REVOKED`

### 로그아웃

`POST /realms/{realm}/logout`

| 인증 | 필요 role | realm |
|---|---|---|
| refresh 쿠키 | - | `internal`, `partner` |

**요청** 본문 없음

**응답** `204 No Content`. 쿠키 삭제 헤더 포함

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | 허용되지 않은 `Origin` |

**규칙** [SES-06](../domain.md#6-세션-규칙-ses) (`LOGOUT`), [SES-07](../domain.md#6-세션-규칙-ses), [SES-08](../domain.md#6-세션-규칙-ses)

- 쿠키의 토큰이 세션의 현재 토큰이든 직전 토큰이든 그 세션을 폐기합니다. 갱신과 달리 직전 토큰을 따로 판정하지 않습니다.
- 경로의 realm과 다른 realm의 세션은 폐기하지 않습니다. 응답은 똑같이 `204`입니다.
- 쿠키 삭제 헤더는 세션 유무와 관계없이 `204` 응답에 항상 넣습니다.
- 감사 로그: `SESSION_REVOKED` (세션을 폐기했을 때만)

### 내 정보

`GET /realms/{realm}/me`

| 인증 | 필요 role | realm |
|---|---|---|
| access token (사용자) | - | `internal`, `partner` |

**응답** `200 OK`

```json
{
  "principalType": "employee",
  "principalId": "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
  "name": "김도윤",
  "email": "kim@dozycoffee.com",
  "roles": ["wms:inbound_manager", "catalog:menu_editor"]
}
```

**규칙**

- `roles`는 DB의 현재 값이라 토큰의 role과 최대 `policy.access-token-ttl`만큼 다를 수 있습니다.
- 파트너는 `roles`가 빈 배열입니다.
- 토큰의 주체 계정이 없거나 `DEACTIVATED`이면 `401 UNAUTHENTICATED`입니다. 개인정보가 파기된 계정이기 때문입니다 ([ACC-04](../domain.md#3-계정-상태-규칙-acc)). 다른 상태는 토큰이 만료까지 유효하므로([SES-07](../domain.md#6-세션-규칙-ses)) 그대로 응답합니다.

### 비밀번호 변경

`POST /realms/{realm}/password/change`

| 인증 | 필요 role | realm |
|---|---|---|
| access token (사용자) | - | `internal`, `partner` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `currentPassword` | string | ✅ | 현재 비밀번호 |
| `newPassword` | string | ✅ | 새 비밀번호 ([PWD-01](../domain.md#4-비밀번호-규칙-pwd)~[PWD-03](../domain.md#4-비밀번호-규칙-pwd)) |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `CURRENT_PASSWORD_MISMATCH` | 현재 비밀번호 불일치 |
| `TOO_MANY_ATTEMPTS` | 현재 비밀번호 확인 실패 반복 |

**규칙** [PWD-06](../domain.md#4-비밀번호-규칙-pwd), [PWD-08](../domain.md#4-비밀번호-규칙-pwd)

- 현재 세션은 토큰의 `sid`로 식별합니다.
- 감사 로그: `PASSWORD_CHANGED`
