package com.dozycoffee.auth.starter.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 테스트 안에서 앞으로 돌릴 수 있는 시계.
 *
 * OAuth2 Client는 발급 응답의 `expires_in`으로 만료 시각을 계산할 때 시스템 시각을 쓰므로, 만료 판단을 확인하는 테스트는
 * 시스템 시각에서 시작해 [advance]로 앞으로 돌립니다.
 */
class MutableClock(
    @Volatile private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
