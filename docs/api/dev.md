# 개발용 API

로그인 기능 없이 다른 서비스가 개발·테스트할 수 있게 하는 API입니다. 공통 규칙은 [conventions.md](conventions.md)를 따릅니다.

> **`local`, `dev` 프로필에서만 등록합니다.** 컨트롤러와 `/dev/**`를 인증 없이 여는 보안 설정이 함께 이 프로필에서만 등록됩니다. 다른 프로필(`prod`, `test`)에서는 둘 다 없어 `/dev/**`가 나머지 경로처럼 거부됩니다(토큰 없이 호출하면 `401 UNAUTHENTICATED`). `prod`와 함께 켜면 기동에 실패합니다 ([configuration.md §2](../configuration.md#2-기동-시-검사), [§4](../configuration.md#4-프로필)).

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
| `principalType` | string | ✅ | [DOM-01](../domain.md#11-realm과-principal-type)의 조합만 허용. `internal`은 `employee`·`system`, `partner`는 `partner` |
| `principalId` | string | ✅ | 임의의 UUID (소문자, 하이픈 포함) |
| `roles` | string[] | | `{audience}:{code}` ([DOM-03](../domain.md#11-realm과-principal-type)). 기본은 빈 배열. 파트너는 비워야 함 ([DOM-04](../domain.md#11-realm과-principal-type)) |

```json
{ "realm": "internal", "principalType": "employee", "principalId": "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", "roles": ["wms:inbound_manager"] }
```

**응답** `200 OK`, `Cache-Control: no-store`

```json
{ "accessToken": "eyJhbGciOiJSUzI1NiIs...", "tokenType": "Bearer", "expiresIn": 600 }
```

**에러**

| code | 조건 |
|---|---|
| `VALIDATION_FAILED` | 필수 필드 누락, 모르는 realm·principalType, realm과 principalType 조합 위반, principalId 형식 오류, role 형식 오류, 파트너에 roles 지정 |

**규칙**

- DB의 계정·role 등록 여부를 확인하지 않습니다.
- 토큰 형식과 서명 키는 실제 발급과 같습니다. `aud`는 [token.md §4](../token.md#4-aud-결정-규칙)를 따릅니다. 직원·system은 요청한 role의 audience, 파트너는 `["store"]`입니다.
- refresh 세션이 없으므로 **모든 토큰에 `sid`를 넣지 않습니다** (직원 토큰 포함). `sid`는 선택 claim이라 서비스 검증에는 영향이 없습니다 ([token.md §3](../token.md#3-claims)).
- 파트너 토큰에 `roles`를 넣으면 거부합니다. 실제로도 파트너에게는 role을 부여하지 않기 때문입니다 ([DOM-04](../domain.md#11-realm과-principal-type)).
- refresh 쿠키는 발급하지 않고, 감사 로그도 남기지 않습니다. 요청 제한 대상이 아닙니다 ([conventions.md §8](conventions.md#8-요청-제한)).
