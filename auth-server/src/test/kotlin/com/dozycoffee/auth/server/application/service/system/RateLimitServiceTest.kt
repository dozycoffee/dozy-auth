package com.dozycoffee.auth.server.application.service.system

import com.dozycoffee.auth.server.adapter.outbound.ratelimit.RateLimitBucket4jAdapter
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import com.dozycoffee.auth.server.support.MutableClock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 용도별 요청 제한 (api/conventions.md §8). 한도는 정책 값(`policy.rate-limit-*`)이고, 카운터는 실제 인메모리 어댑터에
 * 옮길 수 있는 시계를 넣어 씁니다.
 */
class RateLimitServiceTest {
    private val clock = MutableClock()
    private val service = RateLimitService(RateLimitBucket4jAdapter(clock))

    @Test
    fun `IP 요청 제한을 넘으면 TOO_MANY_ATTEMPTS와 다시 시도할 수 있는 시간`() {
        repeat(AuthPolicy.RATE_LIMIT_IP.capacity) { service.check(IP) }

        val ex = assertThrows<TooManyAttemptsException> { service.check(IP) }

        assertEquals("TOO_MANY_ATTEMPTS", ex.code)
        assertEquals(429, ex.status)
        assertTrue(ex.retryAfter > Duration.ZERO && ex.retryAfter <= AuthPolicy.RATE_LIMIT_IP.period, "retryAfter: ${ex.retryAfter}")
    }

    @Test
    fun `IP 요청 제한은 주소마다 따로 셈`() {
        repeat(AuthPolicy.RATE_LIMIT_IP.capacity) { service.check(IP) }

        assertDoesNotThrow { service.check("198.51.100.2") }
    }

    @Test
    fun `IP 요청 제한은 기간이 지나면 다시 허용`() {
        repeat(AuthPolicy.RATE_LIMIT_IP.capacity) { service.check(IP) }

        clock.advance(AuthPolicy.RATE_LIMIT_IP.period)

        assertDoesNotThrow { service.check(IP) }
    }

    @Test
    fun `메일은 이메일 한도까지만 보내고 넘으면 예외 없이 보내지 않음`() {
        val results = List(AuthPolicy.RATE_LIMIT_EMAIL.capacity + 1) { service.tryAcquireMailSend(EMAIL) }

        assertEquals(List(AuthPolicy.RATE_LIMIT_EMAIL.capacity) { true } + false, results)
    }

    @Test
    fun `메일 한도는 이메일마다 따로 세고 대소문자는 구분하지 않음`() {
        repeat(AuthPolicy.RATE_LIMIT_EMAIL.capacity) { service.tryAcquireMailSend(EMAIL) }

        assertFalse(service.tryAcquireMailSend(Email("KIM@dozycoffee.test")))
        assertTrue(service.tryAcquireMailSend(Email("lee@dozycoffee.test")))
    }

    @Test
    fun `메일 한도는 기간이 지나면 다시 허용`() {
        repeat(AuthPolicy.RATE_LIMIT_EMAIL.capacity) { service.tryAcquireMailSend(EMAIL) }

        clock.advance(AuthPolicy.RATE_LIMIT_EMAIL.period)

        assertTrue(service.tryAcquireMailSend(EMAIL))
    }

    @Test
    fun `메일 한도와 IP 한도는 따로 셈`() {
        repeat(AuthPolicy.RATE_LIMIT_EMAIL.capacity) { service.tryAcquireMailSend(EMAIL) }

        assertDoesNotThrow { service.check(IP) }
    }

    @Test
    fun `본인 확인 비밀번호 한도를 넘으면 principal마다 TOO_MANY_ATTEMPTS`() {
        repeat(AuthPolicy.RATE_LIMIT_PASSWORD_CONFIRM.capacity) { service.checkPasswordConfirmation(PRINCIPAL_ID) }

        assertThrows<TooManyAttemptsException> { service.checkPasswordConfirmation(PRINCIPAL_ID) }
        assertDoesNotThrow { service.checkPasswordConfirmation(UUID.randomUUID()) }
    }

    @Test
    fun `본인 확인 비밀번호 한도는 기간이 지나면 다시 허용`() {
        repeat(AuthPolicy.RATE_LIMIT_PASSWORD_CONFIRM.capacity) { service.checkPasswordConfirmation(PRINCIPAL_ID) }

        clock.advance(AuthPolicy.RATE_LIMIT_PASSWORD_CONFIRM.period)

        assertDoesNotThrow { service.checkPasswordConfirmation(PRINCIPAL_ID) }
    }

    private companion object {
        const val IP = "198.51.100.1"
        val EMAIL = Email("kim@dozycoffee.test")
        val PRINCIPAL_ID: UUID = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")
    }
}
