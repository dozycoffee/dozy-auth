package com.dozycoffee.auth.server.application.port.outbound.authorization

/**
 * role 정의를 삭제합니다 (GOV-13).
 *
 * system role 여부와 부여된 principal 수(`ROLE_IN_USE`)는 호출하는 쪽이 먼저 확인합니다. 부여가 남아 있으면 DB 외래 키가 삭제를 막습니다.
 */
interface DeleteRolePort {
    /** 삭제했으면 `true`, 없는 role이면 `false`입니다. */
    fun deleteRole(id: Long): Boolean
}
