package com.dozycoffee.auth.server.application.port.outbound.audit

import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditLogEntry
import com.dozycoffee.auth.server.domain.audit.AuditTargetType
import java.time.Instant
import java.util.UUID

/**
 * 감사 로그를 기간으로 조회합니다 (AUD-04, `GET /admin/audit-logs`).
 *
 * 조회 권한과 기간 상한(`policy.audit-query-max-range`), 기본 기간은 UseCase가 확인합니다.
 */
interface LoadAuditLogsPort {
    /** 조건에 맞는 감사 로그 중 [AuditLogQuery.page] 번째 페이지. 최신순(`occurredAt` 내림차순, 같으면 id 내림차순)입니다. */
    fun findAuditLogs(query: AuditLogQuery): AuditLogPage
}

/**
 * 감사 로그 조회 조건. `null`이거나 비어 있는 조건은 거르지 않습니다.
 *
 * @property from 기간 시작 (포함)
 * @property to 기간 끝 (제외)
 * @property actions 이 중 하나인 action만
 * @property page 0부터 시작하는 페이지 번호
 * @property size 페이지 크기
 */
data class AuditLogQuery(
    val from: Instant,
    val to: Instant,
    val actorId: UUID? = null,
    val targetType: AuditTargetType? = null,
    val targetId: String? = null,
    val actions: Set<AuditAction> = emptySet(),
    val page: Int = 0,
    val size: Int = 20,
) {
    init {
        require(from < to) { "기간 시작은 끝보다 앞이어야 함" }
        require(page >= 0) { "page는 0 이상" }
        require(size > 0) { "size는 1 이상" }
    }
}

/** 감사 로그 한 페이지. [totalElements]는 조건에 맞는 전체 건수입니다. */
data class AuditLogPage(
    val entries: List<AuditLogEntry>,
    val totalElements: Long,
)
