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
