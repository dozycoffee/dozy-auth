package com.dozycoffee.auth.starter

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.core.codec.CharSequenceEncoder
import org.springframework.http.MediaType
import org.springframework.http.codec.EncoderHttpMessageWriter
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.codec.json.JacksonJsonEncoder
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WebFlux 401·403 본문을 서비스의 codec으로 쓰는지, codec이 없을 때 같은 본문을 직접 쓰는지 (starter.md §5).
 *
 * 필드 이름과 값은 api/conventions.md §4의 문자열을 그대로 기대값으로 씁니다.
 */
class DozyReactiveProblemWriterTest {
    private val expected401 =
        mapOf(
            "type" to "https://docs.dozycoffee.com/errors/unauthenticated",
            "title" to "Unauthenticated",
            "status" to 401,
            "instance" to "/me",
            "code" to "UNAUTHENTICATED",
            "traceId" to "4bf92f3577b34da6a3ce929d0e0e4736",
        )

    @Test
    fun `Jackson 3 codec으로 code와 traceId를 최상위 필드로 쓰고 detail은 넣지 않음`() {
        val exchange =
            commence(listOf(EncoderHttpMessageWriter(CharSequenceEncoder.textPlainOnly()), EncoderHttpMessageWriter(JacksonJsonEncoder())))

        assertEquals(expected401, body(exchange))
        assertProblemHeaders(exchange)
    }

    @Test
    fun `Jackson 2 codec으로도 같은 본문을 씀`() {
        @Suppress("DEPRECATION")
        val exchange =
            commence(
                listOf(
                    EncoderHttpMessageWriter(
                        org.springframework.http.codec.json
                            .Jackson2JsonEncoder(),
                    ),
                ),
            )

        assertEquals(expected401, body(exchange))
        assertProblemHeaders(exchange)
    }

    @Test
    fun `ProblemDetail을 쓸 codec이 없으면 같은 본문을 직접 씀`() {
        val exchange = commence(listOf(EncoderHttpMessageWriter(CharSequenceEncoder.allMimeTypes())))

        assertEquals(expected401, body(exchange))
        assertProblemHeaders(exchange)
    }

    @Test
    fun `ServerCodecConfigurer 빈이 없어도 같은 본문을 직접 씀`() {
        val exchange = exchange()

        DozyServerAuthenticationEntryPoint(DozyReactiveProblemWriter.create(StaticListableBeanFactory()))
            .commence(exchange, BadCredentialsException("bad"))
            .block()

        assertEquals(expected401, body(exchange))
        assertProblemHeaders(exchange)
    }

    @Test
    fun `403은 FORBIDDEN 본문`() {
        val exchange = exchange()

        DozyServerAccessDeniedHandler(writer(listOf(EncoderHttpMessageWriter(JacksonJsonEncoder()))))
            .handle(exchange, AccessDeniedException("denied"))
            .block()

        assertEquals(403, exchange.response.statusCode?.value())
        assertEquals(
            expected401 +
                mapOf(
                    "type" to "https://docs.dozycoffee.com/errors/forbidden",
                    "title" to "Forbidden",
                    "status" to 403,
                    "code" to "FORBIDDEN",
                ),
            body(exchange),
        )
        assertNull(exchange.response.headers.getFirst("WWW-Authenticate"))
    }

    @Test
    fun `서비스 필터가 응답에 붙인 X-Trace-Id를 요청 헤더보다 먼저 씀`() {
        val exchange = exchange().apply { response.headers.set("X-Trace-Id", "filter-trace-id") }

        DozyServerAuthenticationEntryPoint(writer(listOf(EncoderHttpMessageWriter(JacksonJsonEncoder()))))
            .commence(exchange, BadCredentialsException("bad"))
            .block()

        assertEquals(listOf("filter-trace-id"), exchange.response.headers.get("X-Trace-Id"))
        assertEquals("filter-trace-id", body(exchange)["traceId"])
    }

    private fun commence(writers: List<HttpMessageWriter<*>>): MockServerWebExchange =
        exchange().also {
            DozyServerAuthenticationEntryPoint(writer(writers)).commence(it, BadCredentialsException("bad")).block()
        }

    private fun writer(writers: List<HttpMessageWriter<*>>) =
        DozyReactiveProblemWriter(DozyTraceIds(StaticListableBeanFactory())) { writers }

    private fun exchange() =
        MockServerWebExchange.from(MockServerHttpRequest.get("/me").header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736"))

    private fun body(exchange: MockServerWebExchange): Map<String, Any?> =
        JsonMapper().readValue(exchange.response.bodyAsString.block(), Map::class.java).mapKeys { it.key as String }

    private fun assertProblemHeaders(exchange: MockServerWebExchange) {
        val headers = exchange.response.headers
        assertEquals(401, exchange.response.statusCode?.value())
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(headers.contentType))
        assertEquals("Bearer", headers.getFirst("WWW-Authenticate"))
        assertEquals(listOf(body(exchange)["traceId"] as String), headers.get("X-Trace-Id"))
    }
}
