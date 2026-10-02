package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.core.PrincipalType
import java.util.UUID

/**
 * 행동한 principal. [type]은 행위 시점의 principal type입니다 (`audit_log.actor_type`).
 *
 * 시스템 작업(부트스트랩, 배치)과 계정을 찾지 못한 로그인 실패(AUD-08)는 행위자가 없으므로 [AuditEvent.actor]를 `null`로 둡니다.
 */
data class AuditActor(
    val id: UUID,
    val type: PrincipalType,
)
