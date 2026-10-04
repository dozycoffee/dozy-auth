package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.server.domain.Page
import com.dozycoffee.auth.server.domain.PageRequest
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditLogEntry
import com.dozycoffee.auth.server.domain.audit.AuditTargetType
import java.time.Instant
import java.util.UUID

/** 감사 로그 조회 (api/admin.md 감사 로그 조회, AUD-04). 필요 role(GOV-14)은 웹 계층이 토큰으로 먼저 검사합니다. */
interface ListAuditLogsUseCase {
    /**
     * 조건에 맞는 감사 로그를 최신순으로 한 페이지 돌려줍니다.
     *
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException 조회하는 사람이 DB 기준 owner가 아님 (AUD-04, GOV-14)
     * @throws com.dozycoffee.auth.server.domain.audit.InvalidAuditQueryPeriodException 기간이 올바르지 않거나 상한을 넘음
     */
    fun listAuditLogs(command: ListAuditLogsCommand): Page<AuditLogEntry>
}

/**
 * 감사 로그 조회 조건. `null`이거나 비어 있는 조건은 보지 않습니다.
 *
 * @property managerId 조회하는 사람
 * @property from 기간 시작 (포함). 기본값은 [com.dozycoffee.auth.server.domain.audit.AuditQueryPeriod.resolve]
 * @property to 기간 끝 (제외)
 * @property actions 이 중 하나인 action만
 */
data class ListAuditLogsCommand(
    val managerId: UUID,
    val from: Instant? = null,
    val to: Instant? = null,
    val actorId: UUID? = null,
    val targetType: AuditTargetType? = null,
    val targetId: String? = null,
    val actions: Set<AuditAction> = emptySet(),
    val page: PageRequest = PageRequest(),
)
