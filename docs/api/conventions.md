# API 공통 규칙

모든 Auth API에 적용되는 규칙과 에러 코드 목록입니다. 엔드포인트 문서는 여기 있는 내용을 반복하지 않습니다.

## 1. 기본 규칙

| 항목 | 규칙 |
|---|---|
| 기본 주소 | `https://auth.dozycoffee.com` (설정값 `AUTH_ISSUER_BASE_URL`) |
| `{realm}` | `internal`, `partner`, `customer` 중 하나. 엔드포인트마다 허용 realm이 정해져 있고, 그 외는 `404` |
| 프로토콜 | HTTPS만 |
| 본문 | `application/json`, UTF-8. 서비스 토큰 발급만 `application/x-www-form-urlencoded` |
| 필드 이름 | camelCase. 서비스 토큰 발급 응답만 OAuth 표준을 따라 snake_case |
| 시각 | ISO 8601, UTC (예: `2026-09-24T05:00:00Z`) |
| id | principal id는 숫자, 세션 id는 UUID 문자열 |
| enum 값 | 계정 상태·action 등은 대문자(`ACTIVE`), principal type과 realm은 소문자(`employee`, `internal`) |
| 민감정보 | 토큰, 이메일은 URL 경로나 쿼리에 넣지 않고 본문으로 받습니다. 예외는 목록 검색어 `q` ([§9](#9-추적과-로그)) |

## 2. 인증 방식

| 방식 | 전달 | 쓰는 곳 | 검사 |
|---|---|---|---|
| 없음 | - | 로그인, 가입, 비밀번호 찾기 등 | 요청 제한 ([§8](#8-요청-제한)) |
| refresh 쿠키 | `dozy_refresh` 쿠키 | 토큰 갱신, 로그아웃 | [§6](#6-refresh-쿠키), [§7](#7-cors와-csrf) |
| access token (사용자) | `Authorization: Bearer` | `/realms/{realm}/**`의 본인 API | [token.md §6](../token.md#6-검증-규칙)의 1~7, 9. `iss`의 realm이 경로의 `{realm}`과 같아야 함. **`aud`는 검사하지 않음.** principal type이 `employee`·`partner`가 아니면(system token) `403 FORBIDDEN` |
| access token (관리) | `Authorization: Bearer` | `/admin/**` | [token.md §6](../token.md#6-검증-규칙) 전부. realm은 `internal`, `aud`에 `auth` 포함. 이후 엔드포인트별 필요 role 검사 |
| system token | `Authorization: Bearer` | `/internal/**` | 관리와 같고, `principalType`이 `system`이어야 함 |
| client 인증 | `Authorization: Basic base64(clientId:clientSecret)` | 서비스 토큰 발급 | OAuth 2.0 `client_secret_basic` |

- 본인 API가 `aud`를 검사하지 않는 이유: role이 없는 직원도 `aud`가 비어 있을 뿐 로그인한 사용자이며, 내 정보 조회와 비밀번호 변경을 할 수 있어야 합니다.
- owner 양도 수락(`POST /admin/owner/transfer/accept`)만 관리 API 중 예외로 `aud` 검사를 하지 않습니다. 양도 대상이 `auth` role이 없는 직원일 수 있기 때문입니다. realm `internal`과 principal type `employee`는 검사합니다.

## 3. 성공 응답

- 감싸는 구조 없이 리소스를 그대로 반환합니다.
- 상태 코드

  | 코드 | 쓰는 경우 |
  |---|---|
  | `200` | 조회, 수정 후 결과 반환 |
  | `201` | 리소스 생성 |
  | `202` | 메일 발송을 접수한 요청. 본문이 없거나 만료 시각만 반환 |
  | `204` | 본문 없는 성공 |

- 목록은 아래 형식입니다. 쿼리 `page`(0부터, 기본 0), `size`(기본 20, 최대 100). 항목 수가 적어 페이지를 나누지 않는 목록은 `page` 없이 `items`만 반환합니다.

  ```json
  {
    "items": [ ... ],
    "page": { "number": 0, "size": 20, "totalElements": 135, "totalPages": 7 }
  }
  ```

## 4. 에러 응답

RFC 9457 Problem Details 형식이며 `Content-Type: application/problem+json`입니다.

```json
{
  "type": "https://docs.dozycoffee.com/errors/invalid-credentials",
  "title": "Invalid credentials",
  "status": 401,
  "detail": "이메일 또는 비밀번호가 올바르지 않습니다.",
  "instance": "/realms/internal/login",
  "code": "INVALID_CREDENTIALS",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `type` | ✅ | `https://docs.dozycoffee.com/errors/{code를 kebab-case로}` |
| `title` | ✅ | 에러 종류의 짧은 영어 이름 |
| `status` | ✅ | HTTP 상태 코드 |
| `detail` | | 디버깅용 설명. 앱은 그대로 보여주지 않음 |
| `instance` | | 요청 경로 |
| `code` | ✅ | 앱이 분기에 쓰는 값 ([§11](#11-에러-코드)) |
| `traceId` | ✅ | `X-Trace-Id` 헤더와 같은 값 |
| `errors` | | 입력값 검증 실패 시 `[{ "field", "code", "message" }]` |

- 앱은 `code`로만 분기하고 문구는 앱이 정합니다.
- `500`은 내부 정보(스택, SQL, 클래스 이름)를 노출하지 않습니다.
- **예외:** 서비스 토큰 발급은 OAuth 2.0 표준 에러 형식(`{"error": "...", "error_description": "..."}`)입니다.

## 5. HTTP 상태 코드

| 상태 | 기준 |
|---|---|
| 400 | 요청 형식 오류, 입력값 검증 실패, 본인 확인용 비밀번호 불일치 |
| 401 | 인증 실패 (토큰 없음·만료·위조, 로그인 실패, 세션 만료). `WWW-Authenticate` 헤더 포함 |
| 403 | 권한 없음, 계정 상태로 인한 거부, 허용되지 않은 `Origin` |
| 404 | 대상 없음 |
| 409 | 현재 상태와 충돌 (중복, 동시 갱신, 상태 전이 불가) |
| 410 | 만료, 사용, 무효화된 1회용 토큰 |
| 429 | 요청 제한 초과. `Retry-After` 헤더 포함 |
| 500 | 서버 오류 |

## 6. refresh 쿠키

| 항목 | 값 |
|---|---|
| 이름 | `dozy_refresh` |
| 값 | refresh token 원문 ([SES-02](../domain.md#6-세션-규칙-ses)) |
| 속성 | `HttpOnly; Secure; SameSite=Strict; Path=/realms/{realm}` |
| `Domain` | 설정하지 않음 (`auth.dozycoffee.com`에만 전송) |
| `Max-Age` | 세션의 남은 절대 만료 시간 |
| 삭제 | 같은 속성에 `Max-Age=0`, 값은 빈 문자열 |

- `Path`가 `/realms/{realm}`이라 해당 realm의 Auth API에만 전송되고 서비스 API에는 전송되지 않습니다. 로그아웃에도 쿠키가 필요해서 `/token`으로 좁히지 않습니다.
- 쿠키를 설정하는 응답: 로그인, 토큰 갱신. 삭제하는 응답: 로그아웃, 파트너 탈퇴.
- 앱과 Auth는 같은 상위 도메인(`.dozycoffee.com`)에 있어야 합니다.

## 7. CORS와 CSRF

- **CORS:** 허용 origin을 명시합니다(`AUTH_CORS_ALLOWED_ORIGINS`). 와일드카드는 쓰지 않고 `Access-Control-Allow-Credentials: true`로 응답합니다. 앱은 `credentials: 'include'`로 요청합니다.
- **CSRF:** 쿠키를 쓰는 API(토큰 갱신, 로그아웃)는 `SameSite=Strict`에 더해 `Origin` 헤더가 허용 목록에 있는지 검사합니다. 없거나 다르면 `403 FORBIDDEN`입니다.

## 8. 요청 제한

- 인증 방식이 "없음"인 API는 IP 단위로 제한합니다. 한도는 `policy.rate-limit`입니다. JWKS는 제외합니다.
- 메일을 보내는 API(가입, 인증 메일 재발송, 비밀번호 찾기)는 같은 이메일에 대한 반복 요청도 제한합니다. 제한에 걸려도 계정 존재 여부가 드러나지 않게 같은 `202`로 응답하고 메일만 보내지 않습니다.
- 서비스 토큰 발급은 요청 제한 대상이 아닙니다. client secret은 난수라 대입 공격이 의미 없고, 실패는 `invalid_client`로만 응답합니다.
- 로그인은 추가로 계정 단위 잠금이 있습니다 ([LGN-01](../domain.md#5-로그인-규칙-lgn)).
- 본인 확인용 비밀번호를 받는 API(비밀번호 변경, 파트너 탈퇴)는 principal 단위로도 제한합니다.
- 초과하면 `429 TOO_MANY_ATTEMPTS`와 `Retry-After`로 응답합니다.
- 카운터는 인스턴스 메모리에 있습니다 ([ADR-0024](../adr/0024-in-memory-rate-limit-and-scheduler.md)).

## 9. 추적과 로그

- 모든 응답에 `X-Trace-Id` 헤더를 넣습니다. 에러 응답의 `traceId`와 같습니다.
- 목록 검색어 `q`는 쿼리 문자열로 받지만 접근 로그에서 값을 가립니다.
- 로그 금지 항목은 [SEC-03](../domain.md#12-민감정보-sec)을 따릅니다.

## 10. 공통 에러

엔드포인트 문서에는 아래 공통 에러를 다시 적지 않고, 그 엔드포인트에만 해당하는 에러만 적습니다.

| 조건 | 에러 |
|---|---|
| 본문 형식 오류, 필수 필드 누락, 형식 위반 | `400 VALIDATION_FAILED` |
| 인증 방식이 access token·system token인 모든 API에서 토큰이 없거나 검증 실패 ([token.md §6](../token.md#6-검증-규칙)의 `iss`·`aud`·principal type 포함) | `401 UNAUTHENTICATED` |
| 관리·내부 API에서 필요 role 없음, 본인 API에 system token 사용 | `403 FORBIDDEN` |
| 요청 제한 대상 API에서 한도 초과 | `429 TOO_MANY_ATTEMPTS` |

## 11. 에러 코드

서비스 토큰 발급의 OAuth 에러(`invalid_client` 등)는 [api/internal.md](internal.md#서비스-토큰-발급)에 있습니다.

| code | status | 의미 |
|---|---|---|
| `VALIDATION_FAILED` | 400 | 입력값 형식 오류, 비밀번호 정책 위반 |
| `CURRENT_PASSWORD_MISMATCH` | 400 | 본인 확인용 현재 비밀번호 불일치 ([PWD-08](../domain.md#4-비밀번호-규칙-pwd)) |
| `UNAUTHENTICATED` | 401 | 토큰 없음·만료·위조 |
| `INVALID_CREDENTIALS` | 401 | 로그인 실패 ([LGN-02](../domain.md#5-로그인-규칙-lgn)) |
| `SESSION_EXPIRED` | 401 | refresh 세션 없음·만료 |
| `SESSION_REVOKED` | 401 | 재사용 탐지로 세션 폐기 |
| `FORBIDDEN` | 403 | 권한 없음, 허용되지 않은 `Origin`, 규칙상 금지된 작업 |
| `PROTECTED_ACCOUNT` | 403 | admin이 owner·admin 계정을 변경, owner 정지·비활성화 ([GOV-02](../domain.md#8-관리-권한-규칙-gov), [GOV-03](../domain.md#8-관리-권한-규칙-gov)) |
| `SELF_GRANT_NOT_ALLOWED` | 403 | 자기 자신에게 role 부여 ([GOV-04](../domain.md#8-관리-권한-규칙-gov)) |
| `EMAIL_NOT_VERIFIED` | 403 | 이메일 인증 전 파트너의 로그인 |
| `ACCOUNT_SUSPENDED` | 403 | 정지된 계정의 로그인 |
| `NOT_FOUND` | 404 | 대상 없음 |
| `DUPLICATE_EMAIL` | 409 | 이미 사용 중인 이메일 |
| `ROLE_CODE_DUPLICATED` | 409 | 같은 audience에 같은 role code |
| `AUDIENCE_CODE_DUPLICATED` | 409 | 같은 audience code |
| `CLIENT_ID_DUPLICATED` | 409 | 같은 `clientId` |
| `ROLE_IN_USE` | 409 | 부여된 principal이 있는 role 삭제 |
| `INVALID_STATE` | 409 | 현재 상태에서 허용되지 않는 작업 ([ACC-01](../domain.md#3-계정-상태-규칙-acc)) |
| `TOKEN_ROTATED` | 409 | 교체 직후 직전 refresh token 사용 ([SES-03](../domain.md#6-세션-규칙-ses)) |
| `VERIFICATION_EXPIRED` | 410 | 만료, 사용, 무효화된 1회용 토큰 ([VER-04](../domain.md#7-verification-규칙-ver)) |
| `TOO_MANY_ATTEMPTS` | 429 | 요청 제한 초과, 계정 잠금 |

새 코드를 추가할 때는 이 표에 먼저 넣고, 코드의 에러 enum과 이름을 맞춥니다.

## 12. 엔드포인트 문서 형식

`api/*.md`의 엔드포인트는 모두 아래 형식을 따릅니다. 흐름(호출 순서)은 파일 맨 앞 "흐름" 절에 둡니다.

```markdown
### {이름}

`{METHOD} {경로}`

| 인증 | 필요 role | realm |
|---|---|---|

**요청** / **응답** / **에러** (공통 에러 제외) / **규칙** (domain.md 규칙 ID와 이 API에만 해당하는 동작)
```
