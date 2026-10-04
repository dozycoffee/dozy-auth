package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.server.domain.AuthPolicy
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 감사 로그 조회 기간 (api/admin.md 감사 로그 조회). */
class AuditQueryPeriodTest {
    @Test
    fun `기간을 주지 않으면 지금까지 기본 조회 기간`() {
        val period = AuditQueryPeriod.resolve(null, null, NOW)

        assertEquals(NOW.minus(AuthPolicy.AUDIT_QUERY_DEFAULT_RANGE), period.from)
        assertEquals(NOW, period.to)
    }

    @Test
    fun `to만 주면 to 앞으로 기본 조회 기간`() {
        val to = NOW.minusSeconds(3600)

        val period = AuditQueryPeriod.resolve(null, to, NOW)

        assertEquals(to.minus(AuthPolicy.AUDIT_QUERY_DEFAULT_RANGE), period.from)
        assertEquals(to, period.to)
    }

    @Test
    fun `from만 주면 지금까지`() {
        val from = NOW.minusSeconds(3600)

        val period = AuditQueryPeriod.resolve(from, null, NOW)

        assertEquals(from, period.from)
        assertEquals(NOW, period.to)
    }

    @Test
    fun `둘 다 주면 그대로`() {
        val from = NOW.minusSeconds(7200)
        val to = NOW.plusSeconds(3600)

        val period = AuditQueryPeriod.resolve(from, to, NOW)

        assertEquals(from, period.from)
        assertEquals(to, period.to)
    }

    @Test
    fun `AUD-04 기간이 최대 기간과 같으면 허용`() {
        val from = NOW.minus(AuthPolicy.AUDIT_QUERY_MAX_RANGE)

        assertEquals(from, AuditQueryPeriod.resolve(from, NOW, NOW).from)
    }

    @Test
    fun `AUD-04 기간이 최대 기간을 넘으면 VALIDATION_FAILED`() {
        val from = NOW.minus(AuthPolicy.AUDIT_QUERY_MAX_RANGE).minusNanos(1000)

        val error = assertFailsWith<InvalidAuditQueryPeriodException> { AuditQueryPeriod.resolve(from, NOW, NOW) }

        assertEquals("VALIDATION_FAILED", error.code)
        assertEquals(400, error.status)
    }

    @Test
    fun `시작이 끝보다 앞이 아니면 VALIDATION_FAILED`() {
        assertFailsWith<InvalidAuditQueryPeriodException> { AuditQueryPeriod.resolve(NOW, NOW, NOW) }
        assertFailsWith<InvalidAuditQueryPeriodException> { AuditQueryPeriod.resolve(NOW.plusSeconds(1), NOW, NOW) }
        assertFailsWith<InvalidAuditQueryPeriodException> { AuditQueryPeriod.resolve(NOW.plusSeconds(1), null, NOW) }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
