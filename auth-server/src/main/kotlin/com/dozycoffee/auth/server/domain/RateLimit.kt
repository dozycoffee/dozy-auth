package com.dozycoffee.auth.server.domain

import java.time.Duration

/**
 * 요청 제한 한도 (domain.md §2 `policy.rate-limit-*`, api/conventions.md §8).
 *
 * [period] 동안 [capacity]회입니다. 한 번에 [capacity]회까지 쓸 수 있고, 쓴 횟수는 [period]에 걸쳐 고르게 다시 채워집니다
 * (예: 1분에 20회면 3초마다 1회). 고정 구간 방식과 달리 구간 경계에서 한도의 두 배가 몰리지 않습니다.
 */
data class RateLimit(
    val capacity: Int,
    val period: Duration,
) {
    init {
        require(capacity > 0) { "요청 제한 횟수는 1 이상이어야 합니다" }
        require(period > Duration.ZERO) { "요청 제한 기간은 0보다 커야 합니다" }
    }
}
