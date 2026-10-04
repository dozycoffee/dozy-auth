package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.RoleCode
import java.util.UUID

/** principal에게서 role 하나를 회수합니다 (api/admin.md role 회수, GOV-05, GOV-08). admin 해임도 `auth:admin` 회수로 합니다. */
interface RevokeRoleUseCase {
    /**
     * 가지지 않은 role을 회수해도 성공입니다 (GOV-08).
     *
     * @throws com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException 없는 principal
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException 정의되지 않은 role
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException GOV-05
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException GOV-02
     */
    fun revokeRole(command: RevokeRoleCommand)
}

/**
 * role 회수 요청.
 *
 * @property manager 요청한 직원. 관리 등급은 토큰이 아니라 DB의 현재 role로 정합니다
 */
data class RevokeRoleCommand(
    val manager: PrincipalKey,
    val principalId: UUID,
    val role: RoleCode,
    val ip: String?,
    val userAgent: String?,
)
