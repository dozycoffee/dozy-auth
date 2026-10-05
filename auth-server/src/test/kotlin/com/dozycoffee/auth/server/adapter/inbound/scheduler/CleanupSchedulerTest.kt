package com.dozycoffee.auth.server.adapter.inbound.scheduler

import com.dozycoffee.auth.server.application.port.inbound.system.CleanUpExpiredDataUseCase
import com.dozycoffee.auth.server.application.port.inbound.system.CleanupResult
import com.dozycoffee.auth.server.domain.AuthPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** AUD-05 정리 배치 스케줄러의 실행 시각과 실패 처리 (configuration.md §11). */
class CleanupSchedulerTest {
    private val useCase = mockk<CleanUpExpiredDataUseCase>()
    private val scheduler = CleanupScheduler(useCase)

    @Test
    fun `policy-cleanup-schedule의 시각과 시간대로 실행`() {
        val scheduled = assertNotNull(CleanupScheduler::class.java.getMethod("run").getAnnotation(Scheduled::class.java))

        assertEquals(AuthPolicy.CLEANUP_SCHEDULE_CRON, scheduled.cron)
        assertEquals(AuthPolicy.CLEANUP_SCHEDULE_ZONE, scheduled.zone)
    }

    @Test
    fun `실행하면 정리 UseCase를 부름`() {
        every { useCase.cleanUp() } returns CleanupResult(sessions = 3, verifications = 2, auditLogs = 1)

        scheduler.run()

        verify(exactly = 1) { useCase.cleanUp() }
    }

    @Test
    fun `실패해도 예외를 밖으로 던지지 않음`() {
        every { useCase.cleanUp() } throws IllegalStateException("DB 연결 실패")

        scheduler.run()

        verify(exactly = 1) { useCase.cleanUp() }
    }
}
