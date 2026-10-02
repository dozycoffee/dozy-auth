package com.dozycoffee.auth.starter

import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 에러 응답의 trace id를 정하는 순서 (starter.md §5). */
class DozyTraceIdsTest {
    private val noTrace = DozyTraceIds(StaticListableBeanFactory(mapOf("tracer" to mockk<Tracer> { every { currentSpan() } returns null })))

    @Test
    fun `Micrometer Tracing이 있으면 현재 trace id를 응답 헤더와 요청 헤더보다 먼저 씀`() {
        val tracer = mockk<Tracer> { every { currentSpan()?.context()?.traceId() } returns "80f198ee56343ba864fe8b2a57d3eff7" }
        val traceIds = DozyTraceIds(StaticListableBeanFactory(mapOf("tracer" to tracer)))

        assertEquals("80f198ee56343ba864fe8b2a57d3eff7", traceIds.resolve(outgoing = "filter-trace-id", incoming = "request-trace-id"))
    }

    @Test
    fun `진행 중인 trace가 없으면 서비스가 이미 응답에 붙인 X-Trace-Id를 요청 헤더보다 먼저 씀`() {
        assertEquals("filter-trace-id", noTrace.resolve(outgoing = "filter-trace-id", incoming = "request-trace-id"))
    }

    @Test
    fun `Micrometer Tracing 빈이 없어도 응답에 붙은 X-Trace-Id를 씀`() {
        val traceIds = DozyTraceIds(StaticListableBeanFactory())

        assertEquals("filter-trace-id", traceIds.resolve(outgoing = "filter-trace-id", incoming = "request-trace-id"))
    }

    @Test
    fun `응답에 X-Trace-Id가 없으면 요청 헤더를 씀`() {
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", noTrace.resolve(outgoing = null, incoming = "4bf92f3577b34da6a3ce929d0e0e4736"))
    }

    @Test
    fun `요청 헤더 형식이 올바르지 않으면 새 값을 만듦`() {
        listOf("<script>alert(1)</script>", "", "a".repeat(65), "trace id").forEach { incoming ->
            val traceId = noTrace.resolve(outgoing = null, incoming = incoming)
            assertTrue(Regex("[0-9a-f]{32}").matches(traceId), "$incoming -> $traceId")
        }
    }

    @Test
    fun `아무 값도 없으면 매번 새 값을 만듦`() {
        val first = noTrace.resolve(outgoing = null, incoming = null)
        val second = noTrace.resolve(outgoing = null, incoming = null)

        assertTrue(Regex("[0-9a-f]{32}").matches(first))
        assertTrue(first != second)
    }
}
