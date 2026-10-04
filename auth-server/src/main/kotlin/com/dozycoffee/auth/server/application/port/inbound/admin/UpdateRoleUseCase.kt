package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.PrincipalKey

/** role 정의의 이름과 설명을 바꿉니다 (api/admin.md role 수정, GOV-13). code와 audience는 바꿀 수 없습니다. */
interface UpdateRoleUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException 없는 role
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException DB의 현재 role에 관리 등급이 없음 (GOV-14), system role
     */
    fun updateRole(command: UpdateRoleCommand): RoleDefinition
}

/**
 * role 수정 요청. `null`인 값은 바꾸지 않습니다.
 *
 * @property description 빈 문자열이면 설명을 지웁니다
 */
data class UpdateRoleCommand(
    val manager: PrincipalKey,
    val roleId: Long,
    val name: String?,
    val description: String?,
    val ip: String?,
    val userAgent: String?,
)
