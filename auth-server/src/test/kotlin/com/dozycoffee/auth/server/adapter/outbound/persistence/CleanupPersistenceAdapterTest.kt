package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.support.CleanupRows.MICROSECOND
import com.dozycoffee.auth.server.support.CleanupRows.insertAuditLogs
import com.dozycoffee.auth.server.support.CleanupRows.insertPrincipal
import com.dozycoffee.auth.server.support.CleanupRows.insertSessions
import com.dozycoffee.auth.server.support.CleanupRows.insertVerification
import com.dozycoffee.auth.server.support.CleanupRows.remainingAuditLogs
import com.dozycoffee.auth.server.support.CleanupRows.remainingSessions
import com.dozycoffee.auth.server.support.CleanupRows.remainingVerifications
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * AUD-05 정리 배치의 삭제 쿼리 (docs/data-model.md §5). 세 어댑터의 삭제 메서드를 실제 SQL로 확인합니다.
 *
 * 각 조건 컬럼마다 기준 시각 1µs 전·같은 시각·1µs 뒤 행을 넣어, 기준 시각 이하만 지우는지 봅니다. 다른 테스트가 남긴 행과
 * 겹치지 않도록 기준 시각을 먼 과거로 잡고, 결과는 넣은 행의 id로만 확인합니다.
 */
@PersistenceAdapterTest
class CleanupPersistenceAdapterTest {
    private val sessions = SessionPersistenceAdapter()
    private val verifications = VerificationPersistenceAdapter()
    private val auditLogs = AuditPersistenceAdapter()

    @Test
    fun `AUD-05 절대 만료 시각이 기준 시각 이하인 세션을 지움`() {
        val principal = insertPrincipal()
        val before = insertSessions(principal, absoluteExpiresAt = CUTOFF.minus(MICROSECOND)).single()
        val exact = insertSessions(principal, absoluteExpiresAt = CUTOFF).single()
        val after = insertSessions(principal, absoluteExpiresAt = CUTOFF.plus(MICROSECOND)).single()

        val deleted = sessions.deleteEndedSessions(CUTOFF, LIMIT)

        assertEquals(2, deleted)
        assertEquals(setOf(after), remainingSessions(listOf(before, exact, after)))
    }

    @Test
    fun `AUD-05 폐기 시각이 기준 시각 이하인 세션을 지움`() {
        val principal = insertPrincipal()
        val notEnded = CUTOFF.plus(Duration.ofDays(1))
        val before = insertSessions(principal, absoluteExpiresAt = notEnded, revokedAt = CUTOFF.minus(MICROSECOND)).single()
        val exact = insertSessions(principal, absoluteExpiresAt = notEnded, revokedAt = CUTOFF).single()
        val after = insertSessions(principal, absoluteExpiresAt = notEnded, revokedAt = CUTOFF.plus(MICROSECOND)).single()
        val live = insertSessions(principal, absoluteExpiresAt = notEnded).single()

        val deleted = sessions.deleteEndedSessions(CUTOFF, LIMIT)

        assertEquals(2, deleted)
        assertEquals(setOf(after, live), remainingSessions(listOf(before, exact, after, live)))
    }

    @Test
    fun `AUD-05 세션은 한 번에 limit개까지만 지움`() {
        val ids = insertSessions(insertPrincipal(), absoluteExpiresAt = CUTOFF, count = 3)

        val deleted = sessions.deleteEndedSessions(CUTOFF, 2)

        assertEquals(2, deleted)
        assertEquals(1, remainingSessions(ids).size)
    }

    @Test
    fun `AUD-05 만료 시각이 기준 시각 이하인 verification을 지움`() {
        val before = insertVerification(insertPrincipal(), expiresAt = CUTOFF.minus(MICROSECOND))
        val exact = insertVerification(insertPrincipal(), expiresAt = CUTOFF)
        val after = insertVerification(insertPrincipal(), expiresAt = CUTOFF.plus(MICROSECOND))

        val deleted = verifications.deleteEndedVerifications(CUTOFF, LIMIT)

        assertEquals(2, deleted)
        assertEquals(setOf(after), remainingVerifications(listOf(before, exact, after)))
    }

    @Test
    fun `AUD-05 사용 시각이 기준 시각 이하인 verification을 지움`() {
        val principal = insertPrincipal()
        val notExpired = CUTOFF.plus(Duration.ofDays(1))
        val before = insertVerification(principal, expiresAt = notExpired, consumedAt = CUTOFF.minus(MICROSECOND))
        val exact = insertVerification(principal, expiresAt = notExpired, consumedAt = CUTOFF)
        val after = insertVerification(principal, expiresAt = notExpired, consumedAt = CUTOFF.plus(MICROSECOND))
        val live = insertVerification(principal, expiresAt = notExpired)

        val deleted = verifications.deleteEndedVerifications(CUTOFF, LIMIT)

        assertEquals(2, deleted)
        assertEquals(setOf(after, live), remainingVerifications(listOf(before, exact, after, live)))
    }

    @Test
    fun `AUD-05 무효화 시각이 기준 시각 이하인 verification을 지움`() {
        val principal = insertPrincipal()
        val notExpired = CUTOFF.plus(Duration.ofDays(1))
        val before = insertVerification(principal, expiresAt = notExpired, invalidatedAt = CUTOFF.minus(MICROSECOND))
        val exact = insertVerification(principal, expiresAt = notExpired, invalidatedAt = CUTOFF)
        val after = insertVerification(principal, expiresAt = notExpired, invalidatedAt = CUTOFF.plus(MICROSECOND))
        val live = insertVerification(principal, expiresAt = notExpired)

        val deleted = verifications.deleteEndedVerifications(CUTOFF, LIMIT)

        assertEquals(2, deleted)
        assertEquals(setOf(after, live), remainingVerifications(listOf(before, exact, after, live)))
    }

    @Test
    fun `AUD-05 verification은 한 번에 limit개까지만 지움`() {
        val ids = (1..3).map { insertVerification(insertPrincipal(), expiresAt = CUTOFF) }

        val deleted = verifications.deleteEndedVerifications(CUTOFF, 2)

        assertEquals(2, deleted)
        assertEquals(1, remainingVerifications(ids).size)
    }

    @Test
    fun `AUD-05 발생 시각이 기준 시각 이하인 감사 로그를 지움`() {
        val before = insertAuditLogs(CUTOFF.minus(MICROSECOND)).single()
        val exact = insertAuditLogs(CUTOFF).single()
        val after = insertAuditLogs(CUTOFF.plus(MICROSECOND)).single()

        val deleted = auditLogs.deleteAuditLogs(CUTOFF, LIMIT)

        assertEquals(2, deleted)
        assertEquals(setOf(after), remainingAuditLogs(listOf(before, exact, after)))
    }

    @Test
    fun `AUD-05 감사 로그는 한 번에 limit개까지만 지움`() {
        val ids = insertAuditLogs(CUTOFF, count = 3)

        val deleted = auditLogs.deleteAuditLogs(CUTOFF, 2)

        assertEquals(2, deleted)
        assertEquals(1, remainingAuditLogs(ids).size)
    }

    private companion object {
        /** 지울 기준 시각 (현재 시각 - 보관 기간). 다른 테스트의 행보다 앞이 되도록 먼 과거로 둡니다. */
        val CUTOFF: Instant = Instant.parse("2001-01-01T00:00:00Z")

        /** 경계 테스트에서 넣은 행을 모두 지울 수 있는 크기. */
        const val LIMIT = 100
    }
}
