package com.dozycoffee.auth.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * 현재 시각은 이 `Clock`으로만 얻습니다 (architecture.md §9).
 *
 * 마이크로초 단위로 끊습니다. PostgreSQL `timestamptz`는 마이크로초까지 저장하면서 그 아래를 반올림하므로,
 * 나노초가 남은 시각을 저장하면 다시 읽은 값이 메모리의 값과 달라집니다 (예: 로그인 직후 쿠키 `Max-Age`가 1초 길어짐).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.tick(Clock.systemUTC(), DB_PRECISION)

    private companion object {
        val DB_PRECISION: Duration = Duration.ofNanos(1_000)
    }
}
