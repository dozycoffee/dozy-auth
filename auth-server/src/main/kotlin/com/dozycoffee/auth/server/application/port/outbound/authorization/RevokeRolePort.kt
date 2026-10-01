package com.dozycoffee.auth.server.application.port.outbound.authorization

import java.util.UUID

/** principal에게서 role을 회수합니다 (GOV-08). 회수는 행 삭제이며 이력은 감사 로그에 남깁니다. */
interface RevokeRolePort {
    /** GOV-08 가지고 있던 role이면 회수하고 `true`, 가지지 않은 role이면 아무것도 바꾸지 않고 `false`입니다. */
    fun revoke(
        principalId: UUID,
        roleId: Long,
    ): Boolean

    /** role을 가진 모든 principal에게서 회수합니다 (role 삭제의 `revokeAll`). 회수한 principal id 목록입니다. */
    fun revokeFromAll(roleId: Long): List<UUID>
}
