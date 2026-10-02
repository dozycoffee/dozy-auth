package com.dozycoffee.auth.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 현재 시각은 이 `Clock`으로만 얻습니다 (architecture.md §9).
 *
 * 시각을 마이크로초 단위로 자릅니다. PostgreSQL `timestamptz`는 마이크로초까지 저장하면서 그 아래를 반올림하므로,
 * 나노초가 남은 시각을 저장하면 다시 읽은 값이 메모리의 값과 달라집니다 (예: 로그인 직후 쿠키 `Max-Age`가 1초 길어짐).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): Clock = MicrosecondClock(Clock.systemUTC())
}

/**
 * [base]의 시각을 마이크로초 단위로 자르는 `Clock`.
 *
 * `Clock.tick(base, 1µs)`는 1밀리초보다 작은 단위에서 `millis()`가 0으로 나누기 오류를 내므로(JDK `TickClock`) 쓰지 않습니다.
 */
internal class MicrosecondClock(
    private val base: Clock,
) : Clock() {
    override fun getZone(): ZoneId = base.zone

    override fun withZone(zone: ZoneId): Clock = MicrosecondClock(base.withZone(zone))

    override fun instant(): Instant = base.instant().truncatedTo(ChronoUnit.MICROS)

    override fun millis(): Long = base.millis()

    override fun equals(other: Any?): Boolean = other is MicrosecondClock && other.base == base

    override fun hashCode(): Int = base.hashCode()

    override fun toString(): String = "MicrosecondClock[$base]"
}
