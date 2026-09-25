# 개발용 API

로그인 기능 없이 다른 서비스가 개발·테스트할 수 있게 하는 API입니다. 공통 규칙은 [conventions.md](conventions.md)를 따릅니다.

> **`local`, `dev` 프로필에서만 등록합니다.** 다른 프로필에서는 컨트롤러 빈이 없어 경로 자체가 `404`입니다. 보안 설정에서도 `prod`일 때 `/dev/**`를 막습니다 ([configuration.md §4](../configuration.md#4-프로필)).

## 엔드포인트

### 개발용 토큰 발급

`POST /dev/tokens`

| 인증 | 필요 role | realm |
|---|---|---|
| 없음 | - | `internal`, `partner` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `realm` | string | ✅ | `internal`, `partner` |
| `principalType` | string | ✅ | [DOM-01](../domain.md#11-realm과-principal-type)의 조합만 허용 |
| `principalId` | number | ✅ | 임의의 양의 정수 |
| `roles` | string[] | | `{audience}:{code}`. 기본은 빈 배열 |

```json
{ "realm": "internal", "principalType": "employee", "principalId": 1, "roles": ["wms:inbound_manager"] }
```

**응답** `200 OK`

```json
{ "accessToken": "eyJhbGciOiJSUzI1NiIs...", "tokenType": "Bearer", "expiresIn": 600 }
```

**에러**

| code | 조건 |
|---|---|
| `VALIDATION_FAILED` | realm과 principalType 조합 위반, role 형식 오류 |

**규칙**

- DB의 계정·role 등록 여부를 확인하지 않습니다.
- 토큰 형식과 서명 키는 실제 발급과 같습니다. `aud`는 [token.md §4](../token.md#4-aud-결정-규칙)를 따르며, system token이면 `sid`를 넣지 않습니다.
- refresh 쿠키는 발급하지 않고, 감사 로그도 남기지 않습니다.
