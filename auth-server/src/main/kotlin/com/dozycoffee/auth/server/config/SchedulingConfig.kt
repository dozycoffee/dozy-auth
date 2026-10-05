package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.inbound.scheduler.CleanupScheduler
import com.dozycoffee.auth.server.application.port.inbound.system.CleanUpExpiredDataUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄러 조립 (architecture.md §2 스케줄러, ADR-0024, configuration.md §11).
 *
 * 정리 배치는 `dozy.auth.cleanup.enabled=false`이면 등록하지 않습니다. `test` 프로필은 꺼서, 테스트끼리 함께 쓰는 DB의 행을
 * 실행 시각에 따라 지우지 않게 합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
    @Bean
    @ConditionalOnBooleanProperty("dozy.auth.cleanup.enabled", matchIfMissing = true)
    fun cleanupScheduler(cleanUpExpiredData: CleanUpExpiredDataUseCase): CleanupScheduler = CleanupScheduler(cleanUpExpiredData)
}
