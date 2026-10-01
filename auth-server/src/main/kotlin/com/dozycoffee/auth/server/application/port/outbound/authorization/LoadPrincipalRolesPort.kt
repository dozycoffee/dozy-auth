package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.core.RoleCode
import java.util.UUID

/** principal이 가진 role을 조회합니다. 토큰의 `roles`와 `aud`를 만들 때 씁니다 (token.md §3, §4). */
interface LoadPrincipalRolesPort {
    /** principal이 가진 role. `{audience}:{code}` 순서이며, role이 없으면 빈 목록입니다. */
    fun findRoleCodes(principalId: UUID): List<RoleCode>
}
