package com.dozycoffee.auth.server.adapter.inbound.web.ratelimit

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.config.security.SecurityConfig
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.support.MutableClock
import com.dozycoffee.auth.server.support.MutableClockConfiguration
import com.dozycoffee.auth.server.support.TestEmployees
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 인증 없는 API의 IP 단위 요청 제한 (api/conventions.md §8, `policy.rate-limit-ip`).
 *
 * 시계를 멈춘 채([MutableClock]) 한도를 채우므로 요청 사이에 버킷이 다시 채워지지 않습니다. 클라이언트 주소는 MockMvc의
 * `remoteAddr`로 정합니다. 프록시 헤더로 주소가 바뀌는 것은 실제 Tomcat을 띄우는 [ForwardedClientIpTest]에서 확인합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
class ClientRateLimitApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var employees: TestEmployees

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `한도를 넘으면 429 TOO_MANY_ATTEMPTS와 Retry-After를 Problem Details로 응답`() {
        val responses = List(LIMIT) { login(IP) }
        assertTrue(responses.none { it.status == 429 })

        val limited = login(IP)

        assertEquals(429, limited.status)
        assertEquals("application/problem+json", limited.contentType)
        val body = json(limited)
        assertEquals("TOO_MANY_ATTEMPTS", body["code"])
        assertEquals(429, body["status"])
        assertEquals("https://docs.dozycoffee.com/errors/too-many-attempts", body["type"])
        assertEquals("/realms/internal/login", body["instance"])
        assertEquals(limited.getHeader("X-Trace-Id"), body["traceId"])
        val perRequest = AuthPolicy.RATE_LIMIT_IP.period.dividedBy(LIMIT.toLong())
        assertEquals(perRequest.seconds.toString(), limited.getHeader("Retry-After"))
    }

    @Test
    fun `429 응답에도 CORS 헤더가 있어 앱이 에러 code와 Retry-After를 읽을 수 있음`() {
        repeat(LIMIT) { login(IP) }

        val limited = login(IP, origin = ALLOWED_ORIGIN)

        assertEquals(429, limited.status)
        assertEquals(ALLOWED_ORIGIN, limited.getHeader("Access-Control-Allow-Origin"))
        assertTrue(limited.getHeaders("Access-Control-Expose-Headers").joinToString(",").contains("Retry-After"))
    }

    @Test
    fun `클라이언트 IP가 다르면 따로 셈`() {
        repeat(LIMIT) { login(IP) }

        assertNotEquals(429, login(OTHER_IP).status)
        assertEquals(429, login(IP).status)
    }

    @Test
    fun `한도 기간이 지나면 다시 허용`() {
        repeat(LIMIT + 1) { login(IP) }

        clock.advance(AuthPolicy.RATE_LIMIT_IP.period)

        assertTrue(List(LIMIT) { login(IP) }.none { it.status == 429 })
    }

    @Test
    fun `AUD-08 IP 요청 제한으로 거부한 로그인은 감사 로그를 남기지 않음`() {
        // 한도는 감사 기록이 남지 않는 형식 오류 요청으로 채웁니다. 이 클래스는 멈춘 시계를 써서, 남긴 기록이 다른 테스트의 조회 기간에 섞이기 때문입니다
        repeat(LIMIT) { login(IP) }
        val before = auditLogCount()

        val limited = login(IP, body = UNKNOWN_ACCOUNT)

        assertEquals(429, limited.status)
        assertEquals(before, auditLogCount())
    }

    @Test
    fun `JWKS, 서비스 토큰 발급, 상태 확인은 요청 제한 대상이 아님`() {
        val excluded =
            listOf(
                HttpMethod.GET to "/.well-known/jwks.json",
                HttpMethod.POST to "/realms/internal/token",
                HttpMethod.GET to "/actuator/health",
            )

        excluded.forEach { (method, path) ->
            val statuses = List(LIMIT + 1) { call(method, path, IP).status }
            assertTrue(statuses.none { it == 429 }, "$method $path: $statuses")
        }
        assertNotEquals(429, login(IP).status)
    }

    @Test
    fun `CORS preflight는 세지 않음`() {
        repeat(LIMIT + 1) {
            mockMvc
                .options("/realms/internal/login") {
                    header("Origin", ALLOWED_ORIGIN)
                    header("Access-Control-Request-Method", "POST")
                    with(remoteAddr(IP))
                }.andReturn()
        }

        assertNotEquals(429, login(IP).status)
    }

    /** 인증 없는 경로를 새로 열면(SecurityConfig.PUBLIC_PATHS) 따로 등록하지 않아도 요청 제한 대상인지 확인합니다. */
    @Test
    fun `인증 없는 경로는 제외 경로가 아니면 모두 요청 제한 대상`() {
        val limitedPaths =
            SecurityConfig.PUBLIC_PATHS
                .map { it.replace("{realm}", "internal") }
                .filterNot { it in SPEC_EXCLUDED_PATHS }
        assertTrue(limitedPaths.isNotEmpty())

        limitedPaths.forEachIndexed { index, path ->
            val ip = "203.0.113.${100 + index}"
            repeat(LIMIT) { call(HttpMethod.POST, path, ip) }
            assertEquals(429, call(HttpMethod.POST, path, ip).status, path)
        }
    }

    private fun login(
        ip: String,
        body: String = "{}",
        origin: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(HttpMethod.POST, "/realms/internal/login") {
                contentType = MediaType.APPLICATION_JSON
                content = body
                origin?.let { header("Origin", it) }
                with(remoteAddr(ip))
            }.andReturn()
            .response

    private fun call(
        method: HttpMethod,
        path: String,
        ip: String,
    ): MockHttpServletResponse = mockMvc.request(method, path) { with(remoteAddr(ip)) }.andReturn().response

    private fun remoteAddr(ip: String) =
        RequestPostProcessor { request ->
            request.remoteAddr = ip
            request
        }

    private fun auditLogCount(): Long = employees.inTransaction { AuditLogTable.selectAll().count() }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        val LIMIT = AuthPolicy.RATE_LIMIT_IP.capacity
        const val IP = "198.51.100.1"
        const val OTHER_IP = "198.51.100.2"
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
        const val UNKNOWN_ACCOUNT = """{"email": "nobody@dozycoffee.test", "password": "password-1234"}"""

        /** 명세가 요청 제한에서 뺀 인증 없는 경로 (api/conventions.md §8). */
        val SPEC_EXCLUDED_PATHS = setOf("/.well-known/jwks.json", "/realms/internal/token", "/actuator/health")
    }
}
