package com.dozycoffee.auth.starter

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/** 스타터만 쓰는 정책 값(domain.md §2)을 고정합니다. */
class StarterPolicyTest {
    @Test
    fun `모르는 kid로 JWKS를 다시 받는 최소 간격은 30초`() {
        assertEquals(Duration.ofSeconds(30), DozyJwtDecoders.JWKS_REFETCH_MIN_INTERVAL)
    }
}
