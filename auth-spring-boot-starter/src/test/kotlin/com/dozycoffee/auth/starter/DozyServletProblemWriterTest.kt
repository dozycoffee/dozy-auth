package com.dozycoffee.auth.starter

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spring MVC 401·403 본문을 서비스의 변환기로 쓰는지, 변환기가 없을 때 같은 본문을 직접 쓰는지 (starter.md §5).
 *
 * 필드 이름과 값은 api/conventions.md §4의 문자열을 그대로 기대값으로 씁니다.
 */
class DozyServletProblemWriterTest {
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
    fun `Jackson 3 변환기로 code와 traceId를 최상위 필드로 쓰고 detail은 넣지 않음`() {
        val response = commence(listOf(StringHttpMessageConverter(), JacksonJsonHttpMessageConverter()))

        assertEquals(expected401, body(response))
        assertProblemHeaders(response)
    }

    @Test
    fun `Jackson 2 변환기로도 같은 본문을 씀`() {
        @Suppress("DEPRECATION")
        val response =
            commence(
                listOf(
                    org.springframework.http.converter.json
                        .MappingJackson2HttpMessageConverter(),
                ),
            )

        assertEquals(expected401, body(response))
        assertProblemHeaders(response)
    }

    @Test
    fun `ProblemDetail을 쓸 변환기가 없으면 같은 본문을 직접 씀`() {
        val response = commence(listOf(StringHttpMessageConverter()))

        assertEquals(expected401, body(response))
        assertProblemHeaders(response)
    }

    @Test
    fun `Spring MVC 변환기를 찾을 수 없어도 같은 본문을 직접 씀`() {
        val writer = DozyServletProblemWriter.create(StaticListableBeanFactory())
        val response = MockHttpServletResponse()

        DozyAuthenticationEntryPoint(writer).commence(request(), response, BadCredentialsException("bad"))

        assertEquals(expected401, body(response))
        assertProblemHeaders(response)
    }

    @Test
    fun `403은 FORBIDDEN 본문`() {
        val response = MockHttpServletResponse()

        DozyAccessDeniedHandler(writer(listOf(JacksonJsonHttpMessageConverter()))).handle(
            request(),
            response,
            AccessDeniedException("denied"),
        )

        assertEquals(403, response.status)
        assertEquals(
            expected401 +
                mapOf(
                    "type" to "https://docs.dozycoffee.com/errors/forbidden",
                    "title" to "Forbidden",
                    "status" to 403,
                    "code" to "FORBIDDEN",
                ),
            body(response),
        )
        assertEquals(null, response.getHeader("WWW-Authenticate"))
    }

    @Test
    fun `서비스 필터가 응답에 붙인 X-Trace-Id를 요청 헤더보다 먼저 씀`() {
        val response = MockHttpServletResponse().apply { setHeader("X-Trace-Id", "filter-trace-id") }

        DozyAuthenticationEntryPoint(writer(listOf(JacksonJsonHttpMessageConverter()))).commence(
            request(),
            response,
            BadCredentialsException("bad"),
        )

        assertEquals(listOf("filter-trace-id"), response.getHeaders("X-Trace-Id"))
        assertEquals("filter-trace-id", body(response)["traceId"])
    }

    private fun commence(converters: List<HttpMessageConverter<*>>): MockHttpServletResponse =
        MockHttpServletResponse().also {
            DozyAuthenticationEntryPoint(writer(converters)).commence(request(), it, BadCredentialsException("bad"))
        }

    private fun writer(converters: List<HttpMessageConverter<*>>) =
        DozyServletProblemWriter(DozyTraceIds(StaticListableBeanFactory())) { converters }

    private fun request() =
        MockHttpServletRequest("GET", "/me").apply {
            addHeader("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
        }

    private fun body(response: MockHttpServletResponse): Map<String, Any?> =
        JsonMapper().readValue(response.contentAsByteArray, Map::class.java).mapKeys { it.key as String }

    private fun assertProblemHeaders(response: MockHttpServletResponse) {
        assertEquals(401, response.status)
        assertTrue(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(MediaType.parseMediaType(response.contentType!!)))
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        assertEquals(listOf(body(response)["traceId"]), response.getHeaders("X-Trace-Id"))
    }
}
