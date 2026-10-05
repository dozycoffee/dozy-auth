package com.dozycoffee.auth.server.application.port.inbound.system

/**
 * AUD-05 정리 배치. 보관 기간이 지난 refresh 세션, verification, 감사 로그를 지웁니다. principal은 지우지 않습니다 (ACC-05).
 *
 * 스케줄러가 `policy.cleanup-schedule`마다 부릅니다. 한 번에 지우는 건수를 제한하고 묶음마다 따로 커밋하므로
 * (data-model.md §5), 중간에 실패해도 앞서 지운 묶음은 남고 다음 실행이 이어서 지웁니다.
 * 인스턴스 여러 대가 함께 실행해도 삭제라 결과가 같습니다 (ADR-0024).
 */
interface CleanUpExpiredDataUseCase {
    fun cleanUp(): CleanupResult
}

/** 테이블별로 지운 행 수. */
data class CleanupResult(
    val sessions: Long,
    val verifications: Long,
    val auditLogs: Long,
)
