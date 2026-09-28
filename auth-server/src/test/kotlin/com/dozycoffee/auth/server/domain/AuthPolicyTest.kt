package com.dozycoffee.auth.server.domain

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * 정책 값(domain.md §2)을 고정합니다.
 *
 * 다른 테스트는 `AuthPolicy`를 참조하므로, 정책 값을 바꿀 때는 domain.md와 이 테스트만 고치면 됩니다.
 */
class AuthPolicyTest {
    @Test
    fun `access token은 10분 동안 유효`() {
        assertEquals(Duration.ofMinutes(10), AuthPolicy.ACCESS_TOKEN_TTL)
    }

    @Test
    fun `JWKS 응답은 300초 동안 캐시`() {
        assertEquals(Duration.ofSeconds(300), AuthPolicy.JWKS_CACHE_MAX_AGE)
    }

    @Test
    fun `서명 키는 최소 3072비트`() {
        assertEquals(3072, AuthPolicy.SIGNING_KEY_SIZE)
    }
}
