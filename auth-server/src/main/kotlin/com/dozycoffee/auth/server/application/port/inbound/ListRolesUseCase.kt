package com.dozycoffee.auth.server.application.port.inbound

import com.dozycoffee.auth.server.domain.authorization.Role

/** role 정의 목록 (api/admin.md role 목록). */
interface ListRolesUseCase {
    /** @param audienceCode 주면 그 audience의 role만. 없는 audience면 빈 목록입니다 */
    fun listRoles(audienceCode: String?): List<RoleDefinition>
}

/**
 * role 정의와 부여 현황 (api/admin.md role 목록의 항목).
 *
 * @property grantedCount 이 role을 가진 principal 수
 */
data class RoleDefinition(
    val role: Role,
    val grantedCount: Long,
)
