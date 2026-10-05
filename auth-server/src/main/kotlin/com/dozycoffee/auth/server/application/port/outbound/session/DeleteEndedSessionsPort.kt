package com.dozycoffee.auth.server.application.port.outbound.session

import java.time.Instant

/** 정리 배치(AUD-05)가 보관 기간이 지난 refresh 세션을 지웁니다. */
interface DeleteEndedSessionsPort {
    /**
     * 만료 시각(`expires_at`) 또는 폐기 시각(`revoked_at`)이 [endedAtOrBefore] 이하인 세션을 최대 [limit]개 지웁니다.
     *
     * @return 지운 세션 수. [limit]보다 작으면 조건에 맞는 세션이 더 없습니다
     */
    fun deleteEndedSessions(
        endedAtOrBefore: Instant,
        limit: Int,
    ): Int
}
