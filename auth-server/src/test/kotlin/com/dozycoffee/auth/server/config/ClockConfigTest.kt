package com.dozycoffee.auth.server.config

import org.junit.jupiter.api.RepeatedTest
import kotlin.test.assertEquals

/** 서버 `Clock`은 DB(`timestamptz`)와 같은 마이크로초 정밀도입니다. */
class ClockConfigTest {
    @RepeatedTest(20)
    fun `현재 시각에 마이크로초 아래 값이 없음`() {
        val now = ClockConfig().clock().instant()

        assertEquals(0, now.nano % 1_000)
    }
}
