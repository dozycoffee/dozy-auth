# 관리 API

관리 콘솔이 쓰는 `/admin/**` API입니다. 공통 규칙은 [conventions.md](conventions.md)를 따릅니다.

- 인증은 모두 "access token (관리)"입니다 ([conventions.md §2](conventions.md#2-인증-방식)). 표의 "필요 role" 중 하나가 있어야 합니다.
- 대상 계정에 대한 보호 규칙은 [GOV-02](../domain.md#8-관리-권한-규칙-gov), [GOV-03](../domain.md#8-관리-권한-규칙-gov)을 따르며, 위반은 `PROTECTED_ACCOUNT`입니다.
- owner 알림 대상은 [AUD-01](../domain.md#11-감사와-알림-aud)의 action으로 정해집니다.

## 흐름

### owner 양도

```mermaid
sequenceDiagram
    participant Old as 현재 owner
    participant Console as 관리 콘솔
    participant Auth as Auth
    participant New as 대상 직원

    Old->>Console: 대상 직원 선택
    Console->>Auth: POST /admin/owner/transfer
    Auth->>Auth: 대상 확인, OWNER_TRANSFER 생성
    Auth-->>New: 수락 메일
    New->>Console: 본인으로 로그인 후 링크 접속
    Console->>Auth: POST /admin/owner/transfer/accept
    Auth->>Auth: owner role 이전, 기존 owner 세션 폐기
    Auth-->>Old: 완료 메일
    Auth-->>Console: 204
```

규칙은 [GOV-09](../domain.md#8-관리-권한-규칙-gov)를 따릅니다.

## 1. 직원

### 직원 초대

`POST /admin/employees`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `email` | string | ✅ | 로그인 이메일 |
| `name` | string | ✅ | 이름 |
| `phone` | string | | 전화번호 |
| `address` | string | | 주소 |
| `roles` | string[] | | 함께 부여할 role (`{audience}:{code}`) |

**응답** `201 Created`

```json
{ "principalId": "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", "status": "PENDING", "invitationExpiresAt": "2026-09-27T05:00:00Z" }
```

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | admin이 `auth:admin` 지정, `auth:owner` 지정 ([GOV-05](../domain.md#8-관리-권한-규칙-gov)) |
| `NOT_FOUND` | 존재하지 않는 role |
| `DUPLICATE_EMAIL` | 이미 사용 중인 직원 이메일 |

**규칙** [VER-01](../domain.md#7-verification-규칙-ver) (`EMPLOYEE_INVITATION`), [GOV-06](../domain.md#8-관리-권한-규칙-gov), [GOV-07](../domain.md#8-관리-권한-규칙-gov)

- `roles`를 생략하거나 빈 목록으로 보내면 role 없이 초대합니다. 같은 role을 여러 번 보내면 하나로 봅니다.
- 에러가 여럿이면 [GOV-15](../domain.md#8-관리-권한-규칙-gov)의 직원 초대 순서를 따릅니다.
- principal, profile, role 부여, verification 발급을 한 트랜잭션에서 처리하고, 메일은 커밋 후 보냅니다. 하나라도 실패하면 계정을 만들지 않습니다.
- 감사 로그: `EMPLOYEE_INVITED`, role을 지정했으면 `ROLE_GRANTED` ([AUD-08](../domain.md#11-감사와-알림-aud))

### 직원 목록

`GET /admin/employees`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 쿼리

| 이름 | 설명 |
|---|---|
| `status` | `PENDING`, `ACTIVE`, `SUSPENDED`, `DEACTIVATED` |
| `role` | 이 role을 가진 직원만 (예: `wms:inbound_manager`) |
| `q` | 이름 또는 이메일 검색어 |
| `page`, `size` | [conventions.md §3](conventions.md#3-성공-응답) |

- 생성 최신순입니다.
- `q`는 이름이나 이메일에 그 문자열이 들어간 직원을 대소문자 구분 없이 찾습니다. `%`, `_`도 글자 그대로 찾습니다. 앞뒤 공백은 무시하며, 비어 있으면 조건이 없는 것으로 봅니다.
- 없는 role로 찾으면 빈 목록입니다. `role` 형식이 틀리면(`{audience}:{code}`가 아님) `VALIDATION_FAILED`입니다.

**응답** `200 OK`

```json
{
  "items": [
    {
      "principalId": "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
      "email": "kim@dozycoffee.com",
      "name": "김도윤",
      "status": "ACTIVE",
      "roles": ["wms:inbound_manager"],
      "createdAt": "2026-09-24T05:00:00Z"
    }
  ],
  "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
}
```

### 직원 상세

`GET /admin/employees/{principalId}`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**응답** `200 OK`

```json
{
  "principalId": "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
  "email": "kim@dozycoffee.com",
  "name": "김도윤",
  "phone": "010-1234-5678",
  "address": "서울시 ...",
  "status": "ACTIVE",
  "roles": ["wms:inbound_manager", "catalog:menu_editor"],
  "lockedUntil": null,
  "invitation": null,
  "createdAt": "2026-09-24T05:00:00Z",
  "updatedAt": "2026-09-24T05:00:00Z"
}
```

- `invitation`은 `PENDING`일 때만 `{ "expiresAt": "..." }`이고, 그 외에는 `null`입니다. `expiresAt`이 지났으면 만료된 초대이며 [초대 재발송](#초대-재발송)으로 다시 보냅니다.
- `lockedUntil`은 로그인 실패로 잠겨 있는 동안만 값이 있고([ACC-02](../domain.md#3-계정-상태-규칙-acc)), 잠금이 풀렸으면 `null`입니다.
- `updatedAt`은 계정 상태나 정보가 마지막으로 바뀐 시각입니다.

**에러**

| code | 조건 |
|---|---|
| `NOT_FOUND` | 없는 id, 직원이 아닌 principal |

- admin도 owner·admin 계정을 조회할 수 있습니다.

### 직원 정보 수정

`PATCH /admin/employees/{principalId}`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 보낸 필드만 수정합니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `name` | string | 이름 |
| `phone` | string \| null | `null`이면 삭제 |
| `address` | string \| null | `null`이면 삭제 |

**응답** `200 OK`. [직원 상세](#직원-상세)와 같은 형식

**에러**

| code | 조건 |
|---|---|
| `PROTECTED_ACCOUNT` | admin이 owner·admin 계정 수정 |
| `NOT_FOUND` | 없는 직원 |
| `INVALID_STATE` | `DEACTIVATED` 계정 |

**규칙** [ACC-07](../domain.md#3-계정-상태-규칙-acc)

- `email`을 보내면 무시하지 않고 `VALIDATION_FAILED`입니다. `name`은 보내면 `null`이나 빈 값일 수 없습니다.
- 감사 로그: `PROFILE_UPDATED` (`detail.fields`, [AUD-07](../domain.md#11-감사와-알림-aud)). 보낸 값이 지금 값과 같아 바뀐 필드가 없으면 남기지 않습니다.

### 초대 재발송

`POST /admin/employees/{principalId}/invitation`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 본문 없음

**응답** `202 Accepted`

```json
{ "invitationExpiresAt": "2026-09-27T05:00:00Z" }
```

**에러**

| code | 조건 |
|---|---|
| `PROTECTED_ACCOUNT` | admin이 owner·admin 계정의 초대 재발송 |
| `NOT_FOUND` | 없는 직원 |
| `INVALID_STATE` | `PENDING`이 아님 |

**규칙** [VER-03](../domain.md#7-verification-규칙-ver)

- 부트스트랩 owner의 초대는 재기동할 때도 재발급됩니다 ([GOV-11](../domain.md#8-관리-권한-규칙-gov)).
- 메일은 지금 profile의 이메일로 커밋 후 보냅니다.
- 감사 로그: 남기지 않음 ([AUD-08](../domain.md#11-감사와-알림-aud))

### 초대 취소

`DELETE /admin/employees/{principalId}/invitation`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `PROTECTED_ACCOUNT` | admin이 owner·admin 계정의 초대 취소 |
| `NOT_FOUND` | 없는 직원 |
| `INVALID_STATE` | `PENDING`이 아님 |

**규칙** [ACC-06](../domain.md#3-계정-상태-규칙-acc)

- [ACC-04](../domain.md#3-계정-상태-규칙-acc)의 처리를 한 트랜잭션에서 합니다. 이메일이 파기되므로 같은 이메일로 다시 초대할 수 있습니다 ([ACC-05](../domain.md#3-계정-상태-규칙-acc)).
- 감사 로그: `ACCOUNT_DEACTIVATED` (`detail.via = "invitation_cancelled"`)

## 2. 파트너

### 파트너 목록

`GET /admin/partners`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 쿼리 `status`, `q`(이름·이메일 검색어), `page`, `size`

**응답** `200 OK`

```json
{
  "items": [
    { "principalId": "0199a3c5-1d4f-7a8b-b2c6-5e9f0a3d7c21", "email": "owner@example.com", "name": "이점주", "status": "ACTIVE", "createdAt": "2026-09-24T05:00:00Z" }
  ],
  "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
}
```

- 매장 할당 정보는 Store 서비스에 있어 포함하지 않습니다.

### 파트너 상세

`GET /admin/partners/{principalId}`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**응답** `200 OK`

```json
{
  "principalId": "0199a3c5-1d4f-7a8b-b2c6-5e9f0a3d7c21",
  "email": "owner@example.com",
  "name": "이점주",
  "phone": "010-1234-5678",
  "address": "서울시 ...",
  "businessNo": "123-45-67890",
  "status": "ACTIVE",
  "lockedUntil": null,
  "createdAt": "2026-09-24T05:00:00Z",
  "updatedAt": "2026-09-24T05:00:00Z"
}
```

**에러**

| code | 조건 |
|---|---|
| `NOT_FOUND` | 없는 id, 파트너가 아닌 principal |

### 파트너 정보 수정

`PATCH /admin/partners/{principalId}`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 보낸 필드만 수정합니다. `name`, `phone`, `address`, `businessNo`

**응답** `200 OK`. [파트너 상세](#파트너-상세)와 같은 형식

**에러**

| code | 조건 |
|---|---|
| `NOT_FOUND` | 없는 파트너 |
| `INVALID_STATE` | `DEACTIVATED` 계정 |

**규칙** [ACC-07](../domain.md#3-계정-상태-규칙-acc)

- 감사 로그: `PROFILE_UPDATED`

## 3. 계정 상태와 비밀번호

아래 API는 직원, 파트너, system client 모두에 씁니다.

### 계정 정지

`POST /admin/principals/{principalId}/suspend`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `reason` | string | ✅ | 감사 로그에 기록. 500자 이하, 공백만으로는 안 됨 |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `PROTECTED_ACCOUNT` | owner 계정, admin이 admin 계정 정지 |
| `NOT_FOUND` | 없는 principal |
| `INVALID_STATE` | `ACTIVE`가 아님 |

**규칙** [ACC-03](../domain.md#3-계정-상태-규칙-acc), [SES-07](../domain.md#6-세션-규칙-ses)

- `reason`이 없거나 형식에 맞지 않으면 `VALIDATION_FAILED`입니다.
- 감사 로그: `ACCOUNT_SUSPENDED` (`detail.reason`)

### 정지 해제

`POST /admin/principals/{principalId}/reactivate`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `PROTECTED_ACCOUNT` | admin이 owner·admin 계정 해제 |
| `NOT_FOUND` | 없는 principal |
| `INVALID_STATE` | `SUSPENDED`가 아님 |

- 계정 탈취로 정지했다면 해제 후 재설정 메일을 보내는 것을 권장합니다.
- 정지할 때 폐기한 세션은 되살리지 않습니다. 해제 후 다시 로그인합니다.
- 감사 로그: `ACCOUNT_REACTIVATED`

### 계정 비활성화

`POST /admin/principals/{principalId}/deactivate`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `reason` | string | ✅ | 감사 로그에 기록. 500자 이하, 공백만으로는 안 됨 |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `PROTECTED_ACCOUNT` | owner 계정, admin이 admin 계정 비활성화 |
| `NOT_FOUND` | 없는 principal |
| `INVALID_STATE` | 이미 `DEACTIVATED` |

- `PENDING` 계정도 비활성화할 수 있습니다(예: 인증하지 않은 파트너). 직원 초대 취소는 [초대 취소](#초대-취소)를 씁니다.

**규칙** [ACC-04](../domain.md#3-계정-상태-규칙-acc), [CLI-04](../domain.md#9-system-client-규칙-cli)

- system client 폐기도 이 API로 합니다. 파트너 본인 탈퇴는 [파트너 탈퇴](account.md#파트너-탈퇴)입니다.
- `reason`이 없거나 형식에 맞지 않으면 `VALIDATION_FAILED`입니다.
- 감사 로그: `ACCOUNT_DEACTIVATED` (`detail.reason`)

### 비밀번호 재설정 메일 발송

`POST /admin/principals/{principalId}/password-reset`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 본문 없음

**응답** `202 Accepted` (본문 없음)

**에러**

| code | 조건 |
|---|---|
| `PROTECTED_ACCOUNT` | admin이 owner·admin 계정에 요청 |
| `NOT_FOUND` | 없는 principal |
| `INVALID_STATE` | `ACTIVE`가 아님, system client |

**규칙** [PWD-05](../domain.md#4-비밀번호-규칙-pwd), [VER-01](../domain.md#7-verification-규칙-ver) (`PASSWORD_RESET`), [VER-03](../domain.md#7-verification-규칙-ver)

- 메일을 보내는 것만으로는 비밀번호와 세션이 바뀌지 않습니다. 탈취가 의심되면 먼저 정지합니다.
- 메일은 지금 profile의 이메일로 커밋 후 보냅니다. 관리자가 고른 계정에 보내므로 이메일 단위 요청 제한([conventions.md §8](conventions.md#8-요청-제한))은 적용하지 않습니다.
- 감사 로그: `PASSWORD_RESET_REQUESTED`

## 4. role 부여

### role 부여

`POST /admin/principals/{principalId}/roles`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `roles` | string[] | ✅ | `{audience}:{code}` 목록 |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | admin이 `auth:admin` 부여, `auth:owner` 부여 ([GOV-05](../domain.md#8-관리-권한-규칙-gov)), system client에 system role 부여 ([GOV-06](../domain.md#8-관리-권한-규칙-gov)) |
| `SELF_GRANT_NOT_ALLOWED` | 자기 자신에게 부여 |
| `PROTECTED_ACCOUNT` | admin이 owner·admin 계정에 부여 |
| `NOT_FOUND` | principal 또는 role 없음 |
| `INVALID_STATE` | 대상이 파트너, `SUSPENDED`, `DEACTIVATED` ([GOV-06](../domain.md#8-관리-권한-규칙-gov), [GOV-07](../domain.md#8-관리-권한-규칙-gov)) |

**규칙** [GOV-04](../domain.md#8-관리-권한-규칙-gov)~[GOV-08](../domain.md#8-관리-권한-규칙-gov), [SES-05](../domain.md#6-세션-규칙-ses)

- admin 임명도 이 API로 `auth:admin`을 부여합니다.
- `roles`가 비었거나 `{audience}:{code}` 형식이 아닌 값이 있으면 `VALIDATION_FAILED`입니다. 같은 role을 여러 번 보내면 하나로 봅니다.
- 에러는 `NOT_FOUND`(principal, role)를 먼저 판단하고, 그 뒤는 [GOV-15](../domain.md#8-관리-권한-규칙-gov)의 순서입니다.
- 동시에 삭제된 role은 없는 role(`NOT_FOUND`)입니다. 부여와 [role 삭제](#role-삭제)가 동시에 오면 차례로 처리합니다.
- 감사 로그: `ROLE_GRANTED` (새로 부여한 role만 `detail.roles`에. 새로 부여한 role이 없으면 남기지 않음)

### role 회수

`DELETE /admin/principals/{principalId}/roles/{role}`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 경로 변수 `role`은 `{audience}:{code}` (예: `/admin/principals/0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f/roles/wms:inbound_manager`)

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | admin이 `auth:admin` 회수, `auth:owner` 회수 |
| `PROTECTED_ACCOUNT` | admin이 owner·admin 계정에서 회수 |
| `NOT_FOUND` | principal 또는 role 정의 없음 |

**규칙** [GOV-05](../domain.md#8-관리-권한-규칙-gov), [GOV-08](../domain.md#8-관리-권한-규칙-gov), [SES-07](../domain.md#6-세션-규칙-ses)

- admin 해임도 이 API로 `auth:admin`을 회수합니다.
- 대상의 상태와 principal type은 보지 않습니다. 정지된 계정에서도 회수할 수 있습니다.
- 경로의 `role`이 `{audience}:{code}` 형식이 아니면 `VALIDATION_FAILED`입니다.
- 감사 로그: `ROLE_REVOKED` (가지고 있던 경우만)

## 5. role 정의와 audience

### role 목록

`GET /admin/roles`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 쿼리 `audience`(audience code, 선택)

**응답** `200 OK` (페이지 없음)

```json
{
  "items": [
    {
      "id": 11,
      "audience": "wms",
      "code": "inbound_manager",
      "fullCode": "wms:inbound_manager",
      "name": "입고 관리자",
      "description": "입고 등록·수정·확정",
      "isSystem": false,
      "grantedCount": 12
    }
  ]
}
```

- `grantedCount`는 이 role을 가진 principal 수입니다.

### role 등록

`POST /admin/roles`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `audience` | string | ✅ | audience code |
| `code` | string | ✅ | [DOM-03](../domain.md#11-realm과-principal-type) |
| `name` | string | ✅ | 표시용 이름 |
| `description` | string | | 설명 |

**응답** `201 Created`. [role 목록](#role-목록)의 항목과 같은 형식

**에러**

| code | 조건 |
|---|---|
| `NOT_FOUND` | audience 없음 |
| `ROLE_CODE_DUPLICATED` | 같은 audience에 같은 code |

**규칙** [GOV-13](../domain.md#8-관리-권한-규칙-gov)

- 감사 로그: `ROLE_DEFINED`

### role 수정

`PATCH /admin/roles/{roleId}`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** `name`, `description`만 받습니다. 보내지 않은 필드는 바꾸지 않고, `description`을 빈 문자열로 보내면 설명을 지웁니다.

**응답** `200 OK`. [role 목록](#role-목록)의 항목과 같은 형식

**에러**

| code | 조건 |
|---|---|
| `VALIDATION_FAILED` | `code`나 `audience`를 보냄 |
| `FORBIDDEN` | system role |
| `NOT_FOUND` | 없는 role |

**규칙** [GOV-13](../domain.md#8-관리-권한-규칙-gov)

- 감사 로그: `ROLE_UPDATED`

### role 삭제

`DELETE /admin/roles/{roleId}`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 쿼리 `revokeAll` (기본 `false`). `true`면 부여된 모든 principal에서 회수한 뒤 삭제합니다.

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | system role |
| `NOT_FOUND` | 없는 role |
| `ROLE_IN_USE` | 부여된 principal이 있는데 `revokeAll=true`가 아님 |

**규칙** [GOV-13](../domain.md#8-관리-권한-규칙-gov)

- 앱은 `ROLE_IN_USE`를 받으면 영향 인원을 보여주고 확인을 받은 뒤 `revokeAll=true`로 다시 요청합니다.
- 감사 로그: 회수한 principal마다 `ROLE_REVOKED`(`detail.roles`, `detail.via = "role_deleted"`), 마지막에 `ROLE_DELETED`(`detail.revokedPrincipals`는 회수한 principal 수)

### audience 목록

`GET /admin/audiences`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**응답** `200 OK` (페이지 없음)

```json
{
  "items": [
    { "id": 1, "code": "wms", "name": "창고 관리", "description": null },
    { "id": 4, "code": "auth", "name": "인증", "description": null }
  ]
}
```

### audience 추가

`POST /admin/audiences`

| 필요 role |
|---|
| `auth:owner` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `code` | string | ✅ | [DOM-03](../domain.md#11-realm과-principal-type). 변경 불가 |
| `name` | string | ✅ | 표시용 이름 |
| `description` | string | | 설명 |

**응답** `201 Created`

```json
{ "id": 5, "code": "order", "name": "주문", "description": null }
```

**에러**

| code | 조건 |
|---|---|
| `AUDIENCE_CODE_DUPLICATED` | 같은 code |

**규칙** [GOV-13](../domain.md#8-관리-권한-규칙-gov)

- 감사 로그: `AUDIENCE_CREATED`

## 6. system client

### system client 목록

`GET /admin/system-clients`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**응답** `200 OK` (페이지 없음)

```json
{
  "items": [
    {
      "principalId": "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
      "clientId": "svc-store",
      "name": "Store 서비스",
      "status": "ACTIVE",
      "roles": ["auth:partner_reader"],
      "secretRotatedAt": "2026-09-24T05:00:00Z",
      "createdAt": "2026-09-24T05:00:00Z"
    }
  ]
}
```

- secret은 포함하지 않습니다. role 변경은 [role 부여](#role-부여)·[role 회수](#role-회수), 폐기는 [계정 비활성화](#계정-비활성화)로 합니다.
- 최근 등록한 순서입니다. 비활성화한 client도 `DEACTIVATED`로 나오며, `clientId`는 바뀐 값(`deleted-{principalId}`, [ACC-04](../domain.md#3-계정-상태-규칙-acc))이고 `roles`는 비어 있습니다.

### system client 등록

`POST /admin/system-clients`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `clientId` | string | ✅ | [CLI-01](../domain.md#9-system-client-규칙-cli) |
| `name` | string | ✅ | 표시용 이름 |
| `roles` | string[] | | 부여할 일반 role |

**응답** `201 Created`

```json
{ "principalId": "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73", "clientId": "svc-store", "clientSecret": "Zr8qL2mX..." }
```

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | system role 지정 ([GOV-06](../domain.md#8-관리-권한-규칙-gov)) |
| `NOT_FOUND` | 없는 role |
| `CLIENT_ID_DUPLICATED` | 같은 `clientId` |

**규칙** [CLI-02](../domain.md#9-system-client-규칙-cli), [CLI-04](../domain.md#9-system-client-규칙-cli), [GOV-06](../domain.md#8-관리-권한-규칙-gov)

- secret을 담은 응답이라 `Cache-Control: no-store`입니다.
- `clientId`가 없거나 [CLI-01](../domain.md#9-system-client-규칙-cli) 형식이 아니면, `name`이 없거나 공백만 있거나 컬럼 길이([data-model.md §3.4](../data-model.md#34-system_client))를 넘으면, `roles`에 `{audience}:{code}` 형식이 아닌 값이 있으면 `VALIDATION_FAILED`입니다.
- `roles`를 생략하거나 빈 목록으로 보내면 role 없이 등록합니다. 같은 role을 여러 번 보내면 하나로 봅니다.
- 에러가 여럿이면 [GOV-15](../domain.md#8-관리-권한-규칙-gov)의 system client 등록 순서를 따릅니다.
- principal, system client, role 부여를 한 트랜잭션에서 처리합니다. 하나라도 실패하면 계정을 만들지 않습니다.
- 비활성화한 client의 `clientId`로 다시 등록할 수 있습니다 ([ACC-04](../domain.md#3-계정-상태-규칙-acc)). 새 principal이 만들어집니다.
- 감사 로그: `SYSTEM_CLIENT_REGISTERED`, role을 지정했으면 `ROLE_GRANTED` ([AUD-08](../domain.md#11-감사와-알림-aud))

### secret 재발급

`POST /admin/system-clients/{principalId}/secret`

| 필요 role |
|---|
| `auth:owner`, `auth:admin` |

**요청** 본문 없음

**응답** `200 OK`

```json
{ "clientSecret": "Qw4nV7pK..." }
```

**에러**

| code | 조건 |
|---|---|
| `NOT_FOUND` | 없는 system client |
| `INVALID_STATE` | `ACTIVE`가 아님 |

**규칙** [CLI-02](../domain.md#9-system-client-규칙-cli), [CLI-03](../domain.md#9-system-client-규칙-cli)

- secret을 담은 응답이라 `Cache-Control: no-store`입니다.
- system client가 아닌 principal은 없는 system client(`NOT_FOUND`)입니다. `SUSPENDED`, `DEACTIVATED`는 `INVALID_STATE`입니다.
- 에러는 `NOT_FOUND`, 관리 등급(`FORBIDDEN`), `INVALID_STATE` 순서로 판단합니다.
- 감사 로그: `CLIENT_SECRET_ROTATED`

## 7. owner 양도

### owner 양도 요청

`POST /admin/owner/transfer`

| 필요 role |
|---|
| `auth:owner` |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `targetPrincipalId` | string | ✅ | 대상 직원의 principal id |

**응답** `202 Accepted`

```json
{ "expiresAt": "2026-09-27T05:00:00Z" }
```

**에러**

| code | 조건 |
|---|---|
| `NOT_FOUND` | 대상 없음 |
| `FORBIDDEN` | DB의 현재 role로 owner가 아님 ([GOV-14](../domain.md#8-관리-권한-규칙-gov)) |
| `INVALID_STATE` | 대상이 `ACTIVE` 직원이 아님, 자기 자신, 진행 중인 양도가 있음 |

**규칙** [GOV-09](../domain.md#8-관리-권한-규칙-gov), [VER-01](../domain.md#7-verification-규칙-ver) (`OWNER_TRANSFER`)

- 에러는 `NOT_FOUND`, `FORBIDDEN`, `INVALID_STATE` 순서로 판단합니다.
- 관리자가 고른 직원에게 보내는 메일이라 이메일 단위 요청 제한([conventions.md §8](conventions.md#8-요청-제한))은 적용하지 않습니다.
- 감사 로그: `OWNER_TRANSFER_REQUESTED`

### owner 양도 수락

`POST /admin/owner/transfer/accept`

| 필요 role |
|---|
| 없음 (internal realm의 employee 토큰. `aud` 검사 예외, [conventions.md §2](conventions.md#2-인증-방식)) |

**요청**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `token` | string | ✅ | 수락 링크의 토큰 |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | 로그인한 사용자가 양도 대상이 아님 |
| `VERIFICATION_EXPIRED` | 만료, 사용, 취소된 양도. 요청한 owner가 지금 owner가 아니거나 대상이 `ACTIVE`가 아니게 된 양도 ([GOV-09](../domain.md#8-관리-권한-규칙-gov)) |

**규칙** [GOV-09](../domain.md#8-관리-권한-규칙-gov), [GOV-10](../domain.md#8-관리-권한-규칙-gov)

- 토큰이 쓸 수 없으면(없음, 만료, 사용, 취소) 로그인한 사용자와 관계없이 `VERIFICATION_EXPIRED`입니다. 쓸 수 있는 토큰이면 양도 대상인지(`FORBIDDEN`)를 본 뒤 요청 이후 바뀐 것이 없는지 확인합니다. `FORBIDDEN`이면 토큰을 소비하지 않습니다.
- 새 owner는 다음 토큰 갱신부터 owner 권한을 갖습니다.
- 이전 owner에게 완료 메일을 보냅니다 ([AUD-03](../domain.md#11-감사와-알림-aud)).
- 감사 로그: `OWNER_TRANSFERRED`

### owner 양도 취소

`DELETE /admin/owner/transfer`

| 필요 role |
|---|
| `auth:owner` |

**응답** `204 No Content`

**에러**

| code | 조건 |
|---|---|
| `FORBIDDEN` | DB의 현재 role로 owner가 아님 ([GOV-14](../domain.md#8-관리-권한-규칙-gov)) |
| `NOT_FOUND` | 진행 중인 양도 없음 (만료된 양도 포함) |

- 에러는 `FORBIDDEN`, `NOT_FOUND` 순서로 판단합니다.
- 발송된 수락 링크는 무효가 됩니다.
- 감사 로그: `OWNER_TRANSFER_CANCELLED`

## 8. 감사 로그

### 감사 로그 조회

`GET /admin/audit-logs`

| 필요 role |
|---|
| `auth:owner` |

**요청** 쿼리

| 이름 | 설명 |
|---|---|
| `from`, `to` | 기간 (ISO 8601). `from` 이상 `to` 미만. 기본은 최근 `policy.audit-query-default-range` |
| `actorId` | 행위자 principal id |
| `targetType`, `targetId` | 대상. `targetType`은 `PRINCIPAL`, `ROLE`, `AUDIENCE`, `SESSION` ([data-model.md §3.11](../data-model.md#311-audit_log)) |
| `action` | action. 여러 개는 쉼표로 구분 |
| `page`, `size` | [conventions.md §3](conventions.md#3-성공-응답) |

- 최신순입니다. 발생 시각이 같으면 나중에 기록한 것이 먼저입니다.
- 기간을 하나만 주면 나머지는 이렇게 정합니다. `from`만 주면 지금까지, `to`만 주면 `to` 앞으로 `policy.audit-query-default-range`입니다.
- `action`, `targetType`은 대소문자를 구분합니다. 모르는 값이 하나라도 있으면 `VALIDATION_FAILED`입니다.
- owner인지는 토큰의 role로 먼저 검사하고, DB의 현재 role로 다시 확인합니다 ([GOV-14](../domain.md#8-관리-권한-규칙-gov)). DB에서 owner가 아니면 기간 검사보다 먼저 `FORBIDDEN`입니다.

**응답** `200 OK`

```json
{
  "items": [
    {
      "id": 1024,
      "occurredAt": "2026-09-24T05:00:00Z",
      "actorId": "0199a3b0-2c7d-7e41-a5f8-3b0c9d6e1f24",
      "actorType": "employee",
      "action": "ROLE_GRANTED",
      "targetType": "PRINCIPAL",
      "targetId": "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
      "detail": { "roles": ["wms:inbound_manager"] },
      "ip": "203.0.113.10"
    }
  ],
  "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
}
```

- 행위자와 대상은 id와 type만 담고, 이름·이메일 같은 개인정보는 담지 않습니다 ([AUD-07](../domain.md#11-감사와-알림-aud)). `actorType`은 토큰의 `principalType`과 같은 소문자 값입니다.
- 행위자나 대상이 없는 기록(시스템 작업, 계정을 찾지 못한 로그인 실패 등 [AUD-08](../domain.md#11-감사와-알림-aud))은 그 id와 type이 `null`입니다. `detail`, `ip`도 없으면 `null`입니다.
- `user_agent`는 응답하지 않습니다.

**에러**

| code | 조건 |
|---|---|
| `VALIDATION_FAILED` | 기간 형식 오류, `from`이 `to`보다 앞이 아님, 기간이 `policy.audit-query-max-range` 초과, `actorId` 형식 오류, 모르는 `action`·`targetType` |
| `FORBIDDEN` | DB의 현재 role로 owner가 아님 |

**규칙** [AUD-04](../domain.md#11-감사와-알림-aud)
