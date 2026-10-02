package com.dozycoffee.auth.server.config

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/** 서버 `Clock`은 DB(`timestamptz`)와 같은 마이크로초 정밀도입니다. */
class ClockConfigTest {
    @RepeatedTest(20)
    fun `현재 시각에 마이크로초 아래 값이 없음`() {
        val now = ClockConfig().clock().instant()

        assertEquals(0, now.nano % 1_000)
    }

    @Test
    fun `마이크로초 아래는 버리고 밀리초 값은 그대로`() {
        val base = Clock.fixed(Instant.parse("2026-10-02T00:00:00.123456789Z"), ZoneOffset.UTC)

        val clock = MicrosecondClock(base)

        assertEquals(Instant.parse("2026-10-02T00:00:00.123456Z"), clock.instant())
        assertEquals(base.millis(), clock.millis())
    }
}
