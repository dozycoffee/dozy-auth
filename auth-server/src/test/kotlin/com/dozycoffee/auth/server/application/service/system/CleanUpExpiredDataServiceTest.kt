package com.dozycoffee.auth.server.application.service.system

import com.dozycoffee.auth.server.adapter.outbound.metrics.MetricsMicrometerAdapter
import com.dozycoffee.auth.server.application.port.inbound.system.CleanupResult
import com.dozycoffee.auth.server.application.service.system.CleanUpExpiredDataService.Companion.BATCH_SIZE
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.support.counted
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * AUD-05 정리 배치의 기준 시각, 묶음 반복, 지표 (configuration.md §10.2).
 * 실제 삭제와 커밋은 `CleanUpExpiredDataIntegrationTest`가 확인합니다.
 */
class CleanUpExpiredDataServiceTest {
    private val batch = mockk<CleanupBatch>()
    private val meters = SimpleMeterRegistry()
    private val service = CleanUpExpiredDataService(batch, MetricsMicrometerAdapter(meters), Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `AUD-05 테이블마다 현재 시각에서 보관 기간을 뺀 시각을 기준으로 지움`() {
        every { batch.deleteSessions(any(), any()) } returns 0
        every { batch.deleteVerifications(any(), any()) } returns 0
        every { batch.deleteAuditLogs(any(), any()) } returns 0

        service.cleanUp()

        verify(exactly = 1) { batch.deleteSessions(NOW.minus(AuthPolicy.SESSION_RETENTION), BATCH_SIZE) }
        verify(exactly = 1) { batch.deleteVerifications(NOW.minus(AuthPolicy.VERIFICATION_RETENTION), BATCH_SIZE) }
        verify(exactly = 1) { batch.deleteAuditLogs(NOW.minus(AuthPolicy.AUDIT_RETENTION), BATCH_SIZE) }
    }

    @Test
    fun `묶음을 가득 채워 지우면 묶음 크기보다 적게 지울 때까지 반복하고 합을 돌려줌`() {
        every { batch.deleteSessions(any(), any()) } returnsMany listOf(BATCH_SIZE, BATCH_SIZE, 3)
        every { batch.deleteVerifications(any(), any()) } returnsMany listOf(BATCH_SIZE, 0)
        every { batch.deleteAuditLogs(any(), any()) } returns 7

        val result = service.cleanUp()

        assertEquals(CleanupResult(sessions = 2L * BATCH_SIZE + 3, verifications = BATCH_SIZE.toLong(), auditLogs = 7), result)
        verify(exactly = 3) { batch.deleteSessions(any(), any()) }
        verify(exactly = 2) { batch.deleteVerifications(any(), any()) }
        verify(exactly = 1) { batch.deleteAuditLogs(any(), any()) }
    }

    @Test
    fun `테이블별로 지운 행 수와 성공한 실행을 지표로 남김`() {
        every { batch.deleteSessions(any(), any()) } returnsMany listOf(BATCH_SIZE, 3)
        every { batch.deleteVerifications(any(), any()) } returns 2
        every { batch.deleteAuditLogs(any(), any()) } returns 0

        service.cleanUp()

        assertEquals(BATCH_SIZE + 3.0, meters.counted("dozy.auth.cleanup.deleted", "table", "refresh_session"))
        assertEquals(2.0, meters.counted("dozy.auth.cleanup.deleted", "table", "verification"))
        assertEquals(0.0, meters.counted("dozy.auth.cleanup.deleted", "table", "audit_log"))
        assertEquals(1.0, meters.counted("dozy.auth.cleanup.runs", "outcome", "success"))
        assertEquals(0.0, meters.counted("dozy.auth.cleanup.runs", "outcome", "failure"))
    }

    @Test
    fun `실패하면 실패한 실행을 세고 앞서 커밋한 묶음의 행 수는 남김`() {
        every { batch.deleteSessions(any(), any()) } returnsMany listOf(BATCH_SIZE, 3)
        every { batch.deleteVerifications(any(), any()) } throws IllegalStateException("DB 연결 실패")

        assertFailsWith<IllegalStateException> { service.cleanUp() }

        assertEquals(BATCH_SIZE + 3.0, meters.counted("dozy.auth.cleanup.deleted", "table", "refresh_session"))
        assertEquals(1.0, meters.counted("dozy.auth.cleanup.runs", "outcome", "failure"))
        assertEquals(0.0, meters.counted("dozy.auth.cleanup.runs", "outcome", "success"))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-05T19:00:00Z")
    }
}
