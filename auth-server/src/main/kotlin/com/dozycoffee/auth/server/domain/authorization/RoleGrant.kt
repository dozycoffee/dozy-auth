package com.dozycoffee.auth.server.domain.authorization

import java.time.Instant
import java.util.UUID

/**
 * principal에게 role 하나를 부여한 사실 (docs/data-model.md §3.9). 회수하면 사라지고 이력은 감사 로그에 남습니다.
 *
 * @property grantedBy 부여한 principal. 부트스트랩(GOV-11)과 수동 복구(GOV-12)는 `null`
 */
data class RoleGrant(
    val principalId: UUID,
    val roleId: Long,
    val grantedBy: UUID?,
    val grantedAt: Instant,
)
