package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.server.domain.AuthException

/** 같은 audience에 같은 code의 role이 이미 있음. */
class RoleCodeDuplicatedException : AuthException("ROLE_CODE_DUPLICATED", 409, "같은 audience에 같은 code의 role이 이미 있습니다.")

/** 같은 code의 audience가 이미 있음. */
class AudienceCodeDuplicatedException : AuthException("AUDIENCE_CODE_DUPLICATED", 409, "같은 code의 audience가 이미 있습니다.")

/**
 * 다른 principal이 이미 owner인데 `auth:owner`를 부여하려 함 (GOV-10). DB의 owner 유일성 인덱스가 막은 경우입니다.
 *
 * 관리 API는 `auth:owner`를 부여하지 않으므로(GOV-05) 정상 흐름에서는 생기지 않습니다. 부트스트랩이 동시에 실행되거나(GOV-11)
 * 양도(GOV-09)에서 기존 owner의 role을 먼저 회수하지 않으면 생깁니다.
 */
class OwnerAlreadyAssignedException : AuthException("INVALID_STATE", 409, "owner는 한 명만 있을 수 있습니다.")

/** admin이 owner·admin 계정을 변경하려 함(GOV-02), 또는 owner를 정지·비활성화하려 함(GOV-03). */
class ProtectedAccountException : AuthException("PROTECTED_ACCOUNT", 403, "보호된 계정은 변경할 수 없습니다.")

/** 자기 자신에게 role을 부여하려 함 (GOV-04). */
class SelfGrantNotAllowedException : AuthException("SELF_GRANT_NOT_ALLOWED", 403, "자기 자신에게 role을 부여할 수 없습니다.")

/**
 * 대상이 role을 받을 수 없음. 파트너(GOV-06, DOM-04)이거나 `SUSPENDED`·`DEACTIVATED`(GOV-07)인 경우입니다.
 *
 * 계정 상태 예외와 코드는 같지만, `account` 도메인을 import하지 않도록 이 도메인에 따로 둡니다 (architecture.md §6.2).
 */
class RoleNotGrantableException(
    message: String,
) : AuthException("INVALID_STATE", 409, message)

/** 없는 role 정의. */
class RoleNotFoundException : AuthException("NOT_FOUND", 404, "role이 없습니다.")

/** 없는 audience. */
class AudienceNotFoundException : AuthException("NOT_FOUND", 404, "audience가 없습니다.")

/** 부여된 principal이 있는 role을 일괄 회수(`revokeAll`) 확인 없이 삭제하려 함 (GOV-13). */
class RoleInUseException(
    holders: Long,
) : AuthException("ROLE_IN_USE", 409, "부여된 principal이 있는 role입니다 (${holders}명). 일괄 회수를 확인한 뒤 다시 요청해 주세요.")

/**
 * owner 양도를 요청할 수 없음 (GOV-09). 대상이 자기 자신이거나 `ACTIVE` 직원이 아니거나, 진행 중인 양도가 이미 있는 경우입니다.
 * 메시지에 대상의 이메일 같은 개인정보를 넣지 않습니다 (SEC-03).
 */
class OwnerTransferNotAllowedException(
    message: String,
) : AuthException("INVALID_STATE", 409, message)

/** 취소할 진행 중인 owner 양도가 없음 (api/admin.md owner 양도 취소). */
class OwnerTransferNotFoundException : AuthException("NOT_FOUND", 404, "진행 중인 owner 양도가 없습니다.")
