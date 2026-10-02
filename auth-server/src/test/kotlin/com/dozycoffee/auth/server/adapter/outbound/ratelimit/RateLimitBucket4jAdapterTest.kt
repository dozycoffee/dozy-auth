package com.dozycoffee.auth.server.adapter.outbound.ratelimit

import com.dozycoffee.auth.server.application.port.outbound.ratelimit.RateLimitKey
import com.dozycoffee.auth.server.application.port.outbound.ratelimit.RateLimitResult
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.RateLimit
import com.dozycoffee.auth.server.support.MutableClock
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 인메모리 요청 제한 카운터 (api/conventions.md §8, ADR-0024). 시계를 옮겨 다시 채워지는 것과 버킷 정리를 확인합니다.
 */
class RateLimitBucket4jAdapterTest {
    private val clock = MutableClock()
    private val adapter = RateLimitBucket4jAdapter(clock)

    @Test
    fun `한도만큼은 허용하고 넘으면 다음 1회가 채워질 때까지의 시간과 함께 거부`() {
        repeat(LIMIT.capacity) { assertEquals(RateLimitResult.Allowed, adapter.tryConsume(IP, LIMIT)) }

        val limited = assertIs<RateLimitResult.Limited>(adapter.tryConsume(IP, LIMIT))

        assertEquals(LIMIT.period.dividedBy(LIMIT.capacity.toLong()), limited.retryAfter)
    }

    @Test
    fun `쓴 횟수는 기간에 걸쳐 고르게 다시 채워짐`() {
        repeat(LIMIT.capacity) { adapter.tryConsume(IP, LIMIT) }

        clock.advance(LIMIT.period.dividedBy(LIMIT.capacity.toLong()))

        assertEquals(RateLimitResult.Allowed, adapter.tryConsume(IP, LIMIT))
        assertIs<RateLimitResult.Limited>(adapter.tryConsume(IP, LIMIT))
    }

    @Test
    fun `기간이 지나면 한도만큼 다시 허용`() {
        repeat(LIMIT.capacity) { adapter.tryConsume(IP, LIMIT) }

        clock.advance(LIMIT.period)

        repeat(LIMIT.capacity) { assertEquals(RateLimitResult.Allowed, adapter.tryConsume(IP, LIMIT)) }
        assertIs<RateLimitResult.Limited>(adapter.tryConsume(IP, LIMIT))
    }

    @Test
    fun `키가 다르면 따로 셈`() {
        repeat(LIMIT.capacity) { adapter.tryConsume(IP, LIMIT) }

        assertEquals(RateLimitResult.Allowed, adapter.tryConsume(RateLimitKey.ClientIp("198.51.100.2"), LIMIT))
    }

    @Test
    fun `종류가 다르면 값이 같아도 따로 셈`() {
        val principalId = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")
        repeat(LIMIT.capacity) { adapter.tryConsume(RateLimitKey.ClientIp(principalId.toString()), LIMIT) }

        assertEquals(RateLimitResult.Allowed, adapter.tryConsume(RateLimitKey.PasswordConfirmation(principalId), LIMIT))
    }

    @Test
    fun `같은 키라도 한도가 다르면 따로 셈`() {
        repeat(LIMIT.capacity) { adapter.tryConsume(IP, LIMIT) }

        assertEquals(RateLimitResult.Allowed, adapter.tryConsume(IP, RateLimit(capacity = 1, period = Duration.ofHours(1))))
    }

    @Test
    fun `이메일은 대소문자를 구분하지 않고 셈`() {
        repeat(LIMIT.capacity) { adapter.tryConsume(RateLimitKey.MailRecipient(Email("Kim@DozyCoffee.test")), LIMIT) }

        assertIs<RateLimitResult.Limited>(adapter.tryConsume(RateLimitKey.MailRecipient(Email("kim@dozycoffee.test")), LIMIT))
    }

    @Test
    fun `이메일 키는 문자열로 바꿔도 주소를 드러내지 않음`() {
        val key = RateLimitKey.MailRecipient(Email("kim@dozycoffee.test"))

        assertEquals("MailRecipient(email=***)", key.toString())
    }

    @Test
    fun `마지막으로 쓴 뒤 기간이 지난 버킷은 메모리에서 지움`() {
        adapter.tryConsume(IP, LIMIT)
        adapter.tryConsume(RateLimitKey.ClientIp("198.51.100.2"), LIMIT)
        assertEquals(2, adapter.bucketCount())

        clock.advance(LIMIT.period.minusSeconds(1))
        adapter.tryConsume(IP, LIMIT)
        clock.advance(Duration.ofSeconds(1))

        assertEquals(1, adapter.bucketCount())
    }

    @Test
    fun `지운 버킷은 가득 찬 상태로 다시 만듦`() {
        repeat(LIMIT.capacity) { adapter.tryConsume(IP, LIMIT) }
        clock.advance(LIMIT.period)
        assertEquals(0, adapter.bucketCount())

        repeat(LIMIT.capacity) { assertEquals(RateLimitResult.Allowed, adapter.tryConsume(IP, LIMIT)) }
        assertIs<RateLimitResult.Limited>(adapter.tryConsume(IP, LIMIT))
    }

    private companion object {
        /** 테스트용 한도. 정책 값과 관계없이 동작만 확인합니다. */
        val LIMIT = RateLimit(capacity = 4, period = Duration.ofMinutes(2))
        val IP = RateLimitKey.ClientIp("198.51.100.1")
    }
}
