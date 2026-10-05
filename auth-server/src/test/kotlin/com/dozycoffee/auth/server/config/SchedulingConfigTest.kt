package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.inbound.scheduler.CleanupScheduler
import com.dozycoffee.auth.server.application.port.inbound.system.CleanUpExpiredDataUseCase
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertEquals

/** 정리 배치 스케줄러 등록 (configuration.md §11). `test` 프로필은 `dozy.auth.cleanup.enabled=false`입니다. */
class SchedulingConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig::class.java)
            .withBean(CleanUpExpiredDataUseCase::class.java, { mockk<CleanUpExpiredDataUseCase>() })

    @Test
    fun `설정하지 않으면 정리 배치를 등록`() {
        runner.run { context -> assertEquals(1, context.getBeansOfType(CleanupScheduler::class.java).size) }
    }

    @Test
    fun `dozy-auth-cleanup-enabled가 false이면 정리 배치를 등록하지 않음`() {
        runner
            .withPropertyValues("dozy.auth.cleanup.enabled=false")
            .run { context -> assertEquals(0, context.getBeansOfType(CleanupScheduler::class.java).size) }
    }
}
