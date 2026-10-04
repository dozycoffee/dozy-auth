package com.dozycoffee.auth.server.application.port.outbound.authorization

import java.util.UUID

/** role을 가진 principal을 조회합니다. 직원 목록의 `role` 조건(api/admin.md 직원 목록)에 씁니다. */
interface LoadRoleHoldersPort {
    /** [roleId]를 가진 principal. 아무도 갖지 않았으면 빈 집합입니다. */
    fun findHolderIds(roleId: Long): Set<UUID>
}
