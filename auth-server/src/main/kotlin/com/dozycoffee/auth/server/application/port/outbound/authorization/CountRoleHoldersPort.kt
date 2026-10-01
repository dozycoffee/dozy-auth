package com.dozycoffee.auth.server.application.port.outbound.authorization

/** role을 가진 principal 수를 셉니다. role 삭제 거부(GOV-13 `ROLE_IN_USE`)와 role 목록의 `grantedCount`에 씁니다. */
interface CountRoleHoldersPort {
    fun countHolders(roleId: Long): Long

    /** [roleIds] 각각의 principal 수. 아무도 갖지 않은 role은 0입니다. */
    fun countHolders(roleIds: Collection<Long>): Map<Long, Long>
}
