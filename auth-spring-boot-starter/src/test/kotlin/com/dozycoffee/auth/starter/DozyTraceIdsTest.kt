package com.dozycoffee.auth.starter

import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import kotlin.test.assertEquals

class DozyTraceIdsTest {
    @Test
    fun `Micrometer Tracing이 있으면 현재 trace id를 요청 헤더보다 먼저 씀`() {
        val tracer = mockk<Tracer> { every { currentSpan()?.context()?.traceId() } returns "80f198ee56343ba864fe8b2a57d3eff7" }
        val beanFactory = StaticListableBeanFactory(mapOf("tracer" to tracer))

        assertEquals("80f198ee56343ba864fe8b2a57d3eff7", DozyTraceIds(beanFactory).resolve(incoming = "4bf92f3577b34da6a3ce929d0e0e4736"))
    }

    @Test
    fun `진행 중인 trace가 없으면 요청 헤더를 씀`() {
        val tracer = mockk<Tracer> { every { currentSpan() } returns null }
        val beanFactory = StaticListableBeanFactory(mapOf("tracer" to tracer))

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", DozyTraceIds(beanFactory).resolve(incoming = "4bf92f3577b34da6a3ce929d0e0e4736"))
    }
}
