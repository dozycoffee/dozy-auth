package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.server.domain.AuthPolicy
import java.time.Duration
import java.time.Instant

/**
 * 감사 로그 조회 기간 (AUD-04, api/admin.md 감사 로그 조회). [from] 이상 [to] 미만이고, 길이는 `policy.audit-query-max-range` 이하입니다.
 */
data class AuditQueryPeriod private constructor(
    val from: Instant,
    val to: Instant,
) {
    companion object {
        /**
         * 요청한 기간을 정합니다. 주지 않은 끝은 아래처럼 채웁니다.
         *
         * - 둘 다 없음: 지금까지 최근 `policy.audit-query-default-range`
         * - `to`만 있음: `to`에서 `policy.audit-query-default-range` 앞부터
         * - `from`만 있음: 지금까지
         *
         * @throws InvalidAuditQueryPeriodException 시작이 끝보다 앞이 아니거나, 기간이 `policy.audit-query-max-range`를 넘음
         */
        fun resolve(
            from: Instant?,
            to: Instant?,
            now: Instant,
        ): AuditQueryPeriod {
            val end = to ?: now
            val start = from ?: (end - AuthPolicy.AUDIT_QUERY_DEFAULT_RANGE)
            if (start >= end) throw InvalidAuditQueryPeriodException("조회 기간의 시작은 끝보다 앞이어야 합니다.")
            if (Duration.between(start, end) > AuthPolicy.AUDIT_QUERY_MAX_RANGE) {
                throw InvalidAuditQueryPeriodException("조회 기간이 최대 기간을 넘습니다.")
            }
            return AuditQueryPeriod(start, end)
        }
    }
}
