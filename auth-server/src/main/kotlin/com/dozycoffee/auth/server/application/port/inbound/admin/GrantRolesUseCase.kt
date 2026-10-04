package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.RoleCode
import java.util.UUID

/** principal에게 role을 부여합니다 (api/admin.md role 부여, GOV-04~08). admin 임명도 `auth:admin` 부여로 합니다. */
interface GrantRolesUseCase {
    /**
     * 하나라도 부여할 수 없으면 아무것도 부여하지 않습니다 (GOV-08).
     *
     * @throws com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException 없는 principal
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException 정의되지 않은 role
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException GOV-05, system client에 system role (GOV-06)
     * @throws com.dozycoffee.auth.server.domain.authorization.SelfGrantNotAllowedException GOV-04
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException GOV-02
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleNotGrantableException 파트너(GOV-06), `SUSPENDED`·`DEACTIVATED`(GOV-07)
     */
    fun grantRoles(command: GrantRolesCommand)
}

/**
 * role 부여 요청.
 *
 * @property manager 요청한 직원. 관리 등급은 토큰이 아니라 DB의 현재 role로 정합니다
 * @property roles 부여할 role. 중복은 하나로 봅니다. 비어 있으면 안 됩니다
 */
data class GrantRolesCommand(
    val manager: PrincipalKey,
    val principalId: UUID,
    val roles: Set<RoleCode>,
    val ip: String?,
    val userAgent: String?,
) {
    init {
        require(roles.isNotEmpty()) { "부여할 role이 없습니다." }
    }
}
