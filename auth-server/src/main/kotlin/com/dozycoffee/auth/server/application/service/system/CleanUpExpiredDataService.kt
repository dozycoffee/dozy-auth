package com.dozycoffee.auth.server.application.service.system

import com.dozycoffee.auth.server.application.port.inbound.system.CleanUpExpiredDataUseCase
import com.dozycoffee.auth.server.application.port.inbound.system.CleanupResult
import com.dozycoffee.auth.server.application.port.outbound.metrics.CleanupOutcome
import com.dozycoffee.auth.server.application.port.outbound.metrics.CleanupTarget
import com.dozycoffee.auth.server.application.port.outbound.metrics.RecordMetricsPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * AUD-05 정리 배치.
 *
 * - 기준 시각은 시작할 때 한 번 읽고, 기준 시각에서 보관 기간을 뺀 시각 **이하**인 행을 지웁니다. 보관 기간이 막 끝난 행(정확히 그 시각)도 지웁니다.
 * - 테이블마다 [BATCH_SIZE]개씩 지우고, 지운 수가 [BATCH_SIZE]보다 작으면 다음 테이블로 넘어갑니다. 묶음마다 따로 커밋합니다
 *   ([CleanupBatch], data-model.md §5). 이 메서드 전체를 트랜잭션으로 묶지 않습니다.
 * - 지표(configuration.md §10.2): 커밋한 묶음마다 지운 행 수를, 끝나면 실행 결과를 남깁니다. 실패해도 앞서 커밋한 묶음은 세어져 있습니다.
 */
@Service
class CleanUpExpiredDataService(
    private val batch: CleanupBatch,
    private val recordMetrics: RecordMetricsPort,
    private val clock: Clock,
) : CleanUpExpiredDataUseCase {
    override fun cleanUp(): CleanupResult {
        val now = clock.instant()
        val sessionCutoff = now.minus(AuthPolicy.SESSION_RETENTION)
        val verificationCutoff = now.minus(AuthPolicy.VERIFICATION_RETENTION)
        val auditCutoff = now.minus(AuthPolicy.AUDIT_RETENTION)
        val result =
            try {
                CleanupResult(
                    sessions = deleteAll(CleanupTarget.REFRESH_SESSION) { batch.deleteSessions(sessionCutoff, BATCH_SIZE) },
                    verifications =
                        deleteAll(CleanupTarget.VERIFICATION) { batch.deleteVerifications(verificationCutoff, BATCH_SIZE) },
                    auditLogs = deleteAll(CleanupTarget.AUDIT_LOG) { batch.deleteAuditLogs(auditCutoff, BATCH_SIZE) },
                )
            } catch (e: Exception) {
                recordMetrics.cleanupFinished(CleanupOutcome.FAILURE)
                throw e
            }
        recordMetrics.cleanupFinished(CleanupOutcome.SUCCESS)
        return result
    }

    /** 묶음 삭제를 [BATCH_SIZE]보다 적게 지울 때까지 반복하고 지운 수의 합을 돌려줍니다. */
    private fun deleteAll(
        table: CleanupTarget,
        deleteBatch: () -> Int,
    ): Long {
        var total = 0L
        do {
            val deleted = deleteBatch()
            // 지운 것이 없으면 세지 않습니다. 한 번도 지우지 않은 테이블의 counter는 만들어지지 않습니다 (configuration.md §10.2)
            if (deleted > 0) recordMetrics.cleanupDeleted(table, deleted)
            total += deleted
        } while (deleted >= BATCH_SIZE)
        return total
    }

    internal companion object {
        /** 한 트랜잭션에서 지우는 최대 행 수 (data-model.md §5). */
        const val BATCH_SIZE = 1000
    }
}
