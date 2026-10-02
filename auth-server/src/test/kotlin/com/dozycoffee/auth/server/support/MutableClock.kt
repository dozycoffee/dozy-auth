package com.dozycoffee.auth.server.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 테스트가 직접 옮기는 시계. 시간이 지나야 바뀌는 동작(예: 요청 제한 버킷이 다시 채워짐)을 기다리지 않고 확인합니다.
 *
 * 처음에는 [TokenFixtures.NOW]이고, [advance]로만 움직입니다.
 */
class MutableClock(
    @Volatile private var now: Instant = TokenFixtures.NOW,
) : Clock() {
    fun advance(duration: Duration) {
        now += duration
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

/** 서버의 `Clock` 빈을 [MutableClock]으로 바꿉니다. 이 설정을 `@Import`한 테스트는 별도의 스프링 컨텍스트를 씁니다. */
@TestConfiguration(proxyBeanMethods = false)
class MutableClockConfiguration {
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock()
}
