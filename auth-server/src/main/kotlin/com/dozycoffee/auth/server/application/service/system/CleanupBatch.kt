package com.dozycoffee.auth.server.application.service.system

import com.dozycoffee.auth.server.application.port.outbound.audit.DeleteAuditLogsPort
import com.dozycoffee.auth.server.application.port.outbound.session.DeleteEndedSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.verification.DeleteEndedVerificationsPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 정리 배치(AUD-05)의 한 묶음 삭제. 메서드 하나가 트랜잭션 하나입니다 (data-model.md §5).
 *
 * [CleanUpExpiredDataService]가 묶음마다 부릅니다. 같은 클래스 안에서 부르면 `@Transactional`이 적용되지 않으므로 빈을 나눴습니다.
 * 세 테이블은 서로를 참조하지 않으므로(FK 없음) 지우는 순서에 제약이 없습니다.
 */
@Component
class CleanupBatch(
    private val deleteEndedSessions: DeleteEndedSessionsPort,
    private val deleteEndedVerifications: DeleteEndedVerificationsPort,
    private val deleteAuditLogs: DeleteAuditLogsPort,
) {
    @Transactional
    fun deleteSessions(
        endedAtOrBefore: Instant,
        limit: Int,
    ): Int = deleteEndedSessions.deleteEndedSessions(endedAtOrBefore, limit)

    @Transactional
    fun deleteVerifications(
        endedAtOrBefore: Instant,
        limit: Int,
    ): Int = deleteEndedVerifications.deleteEndedVerifications(endedAtOrBefore, limit)

    @Transactional
    fun deleteAuditLogs(
        occurredAtOrBefore: Instant,
        limit: Int,
    ): Int = deleteAuditLogs.deleteAuditLogs(occurredAtOrBefore, limit)
}
