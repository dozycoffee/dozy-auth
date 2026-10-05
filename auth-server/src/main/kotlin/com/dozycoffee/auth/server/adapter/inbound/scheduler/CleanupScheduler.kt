package com.dozycoffee.auth.server.adapter.inbound.scheduler

import com.dozycoffee.auth.server.application.port.inbound.system.CleanUpExpiredDataUseCase
import com.dozycoffee.auth.server.domain.AuthPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * AUD-05 정리 배치를 `policy.cleanup-schedule`마다 실행합니다 (ADR-0024, configuration.md §11). 지표는 UseCase가 남깁니다.
 *
 * - 결과는 테이블별 삭제 건수만 로그로 남깁니다. 지운 행의 내용(계정, 토큰 해시 등)은 남기지 않습니다 (SEC-03).
 * - 실패하면 오류 로그만 남기고 다음 실행을 기다립니다. 앞서 커밋한 묶음은 남으며 다음 실행이 이어서 지웁니다.
 */
class CleanupScheduler(
    private val cleanUpExpiredData: CleanUpExpiredDataUseCase,
) {
    @Scheduled(cron = AuthPolicy.CLEANUP_SCHEDULE_CRON, zone = AuthPolicy.CLEANUP_SCHEDULE_ZONE)
    fun run() {
        val result =
            try {
                cleanUpExpiredData.cleanUp()
            } catch (e: Exception) {
                log.error("정리 배치 실패. 다음 실행에서 이어서 지웁니다", e)
                return
            }
        log.info(
            "정리 배치 완료: refresh_session {}건, verification {}건, audit_log {}건 삭제",
            result.sessions,
            result.verifications,
            result.auditLogs,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(CleanupScheduler::class.java)
    }
}
