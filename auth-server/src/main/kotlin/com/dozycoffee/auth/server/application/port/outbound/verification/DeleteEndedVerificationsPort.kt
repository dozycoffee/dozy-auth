package com.dozycoffee.auth.server.application.port.outbound.verification

import java.time.Instant

/** 정리 배치(AUD-05)가 보관 기간이 지난 verification을 지웁니다. */
interface DeleteEndedVerificationsPort {
    /**
     * 만료(`expires_at`), 사용(`consumed_at`), 무효화(`invalidated_at`) 시각 중 하나라도 [endedAtOrBefore] 이하인 verification을
     * 최대 [limit]개 지웁니다.
     *
     * @return 지운 verification 수. [limit]보다 작으면 조건에 맞는 verification이 더 없습니다
     */
    fun deleteEndedVerifications(
        endedAtOrBefore: Instant,
        limit: Int,
    ): Int
}
