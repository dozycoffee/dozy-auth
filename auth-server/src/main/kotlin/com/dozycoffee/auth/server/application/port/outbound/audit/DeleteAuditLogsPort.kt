package com.dozycoffee.auth.server.application.port.outbound.audit

import java.time.Instant

/** 정리 배치(AUD-05)가 보관 기간이 지난 감사 로그를 지웁니다. */
interface DeleteAuditLogsPort {
    /**
     * 발생 시각(`occurred_at`)이 [occurredAtOrBefore] 이하인 감사 로그를 최대 [limit]개 지웁니다.
     *
     * @return 지운 감사 로그 수. [limit]보다 작으면 조건에 맞는 기록이 더 없습니다
     */
    fun deleteAuditLogs(
        occurredAtOrBefore: Instant,
        limit: Int,
    ): Int
}
