package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.PrincipalKey

/** role 정의를 삭제합니다 (api/admin.md role 삭제, GOV-13). */
interface DeleteRoleUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException 없는 role
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException system role
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleInUseException 부여된 principal이 있는데 [DeleteRoleCommand.revokeAll]이 아님
     */
    fun deleteRole(command: DeleteRoleCommand)
}

/**
 * role 삭제 요청.
 *
 * @property revokeAll `true`면 부여된 모든 principal에게서 회수한 뒤 삭제합니다 (사용자가 영향 인원을 확인한 요청)
 */
data class DeleteRoleCommand(
    val manager: PrincipalKey,
    val roleId: Long,
    val revokeAll: Boolean,
    val ip: String?,
    val userAgent: String?,
)
