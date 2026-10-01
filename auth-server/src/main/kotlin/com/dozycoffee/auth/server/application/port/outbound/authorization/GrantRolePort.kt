package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.server.domain.authorization.OwnerAlreadyAssignedException
import com.dozycoffee.auth.server.domain.authorization.RoleGrant

/**
 * principal에게 role을 부여합니다 (GOV-08).
 *
 * 여러 role을 한 번에 부여할 때는 UseCase가 한 트랜잭션 안에서 role마다 호출합니다. 하나라도 예외가 나면 트랜잭션이 되돌려져
 * 전체가 적용되지 않습니다. 부여 권한 규칙(GOV-02~07)은 호출하는 쪽이 먼저 확인합니다.
 */
interface GrantRolePort {
    /**
     * GOV-08 이미 가진 role이면 아무것도 바꾸지 않고 `false`, 새로 부여했으면 `true`입니다. 감사 로그(`ROLE_GRANTED`)에는 `true`인 role만 남깁니다.
     *
     * @throws OwnerAlreadyAssignedException `auth:owner`를 부여하는데 다른 principal이 이미 owner일 때 (GOV-10)
     */
    fun grant(grant: RoleGrant): Boolean
}
