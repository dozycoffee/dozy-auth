package com.dozycoffee.auth.server.adapter.inbound.web

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import com.dozycoffee.auth.server.support.TestEmployees.Companion.WRONG_PASSWORD
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.HttpCookie
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 지표, 추적, 로그 (configuration.md §10, api/conventions.md §9, SEC-03). 실제 서버 구성(Actuator, Micrometer Tracing)으로 확인합니다.
 *
 * 지표 이름, 태그, 헤더 이름, 경로는 명세의 문자열 그대로 기대값으로 씁니다. 지표 저장소는 컨텍스트를 함께 쓰는 다른 테스트의 요청도
 * 세므로 값 대신 있는지만 봅니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
class ObservabilityApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var loggingSystem: LoggingSystem

    private val jsonMapper = JsonMapper.builder().build()

    @AfterEach
    fun cleanUp() {
        employees.cleanUp()
        VERBOSE_LOGGERS.forEach { loggingSystem.setLogLevel(it, null) }
    }

    // Actuator

    @Test
    fun `prometheus는 인증 없이 열리고 로그인과 토큰 발급 지표를 보여줌`() {
        val employee = employees.create()
        login(employee.email, PASSWORD)
        login(employee.email, WRONG_PASSWORD)

        val response = mockMvc.get("/actuator/prometheus").andReturn().response

        assertEquals(200, response.status)
        val body = response.contentAsString
        assertTrue(body.contains("""dozy_auth_login_succeeded_total{realm="internal"}"""), "로그인 성공 지표 없음")
        assertTrue(body.contains("""dozy_auth_login_failed_total{realm="internal",reason="INVALID_CREDENTIALS"}"""), "로그인 실패 지표 없음")
        assertTrue(body.contains("""dozy_auth_token_issued_total{kind="login",realm="internal"}"""), "토큰 발급 지표 없음")
        // 태그에 이메일, IP 같은 값을 쓰지 않음
        assertFalse(body.contains(employee.email))
        assertFalse(body.contains("127.0.0.1"))
    }

    @Test
    fun `health와 prometheus 말고 다른 Actuator 엔드포인트는 열지 않음`() {
        listOf("/actuator", "/actuator/metrics", "/actuator/env", "/actuator/beans", "/actuator/loggers").forEach { path ->
            assertNotEquals(
                200,
                mockMvc
                    .get(path)
                    .andReturn()
                    .response.status,
                path,
            )
        }
        assertEquals(
            200,
            mockMvc
                .get("/actuator/health")
                .andReturn()
                .response.status,
        )
    }

    // 추적

    @Test
    fun `요청의 traceparent가 있으면 그 trace id를 X-Trace-Id와 에러의 traceId로 씀`() {
        val response =
            mockMvc
                .get("/realms/internal/me") { header("traceparent", "00-$PARENT_TRACE_ID-00f067aa0ba902b7-01") }
                .andReturn()
                .response

        assertEquals(401, response.status)
        assertEquals(PARENT_TRACE_ID, response.getHeader("X-Trace-Id"))
        assertEquals(PARENT_TRACE_ID, json(response)["traceId"])
    }

    @Test
    fun `X-Trace-Id는 그 요청의 로그에 남는 trace id와 같음`(output: CapturedOutput) {
        // 서비스 토큰 발급은 요청마다 info 로그를 한 줄 남김 (api/internal.md)
        val response = mockMvc.post("/realms/internal/token").andReturn().response

        val traceId = checkNotNull(response.getHeader("X-Trace-Id"))
        assertTrue(Regex("[0-9a-f]{32}").matches(traceId), traceId)
        val line = output.all.lines().last { it.contains("서비스 토큰 발급") }
        assertTrue(line.contains(traceId), line)
    }

    // 민감정보 (SEC-03)

    @Test
    fun `SEC-03 디버그 로그에도 비밀번호, 토큰, 쿠키, Authorization 헤더 값이 남지 않음`(output: CapturedOutput) {
        VERBOSE_LOGGERS.forEach { loggingSystem.setLogLevel(it, LogLevel.DEBUG) }
        val employee = employees.create()
        login(employee.email, WRONG_PASSWORD)
        val loggedIn = login(employee.email, PASSWORD)
        val accessToken = json(loggedIn)["accessToken"] as String
        val refreshToken = refreshCookie(loggedIn)
        mockMvc.get("/realms/internal/me") { header("Authorization", "Bearer $accessToken") }
        val refreshed = refresh(refreshToken)
        val rotatedToken = refreshCookie(refreshed)
        val refreshedAccessToken = json(refreshed)["accessToken"] as String

        // 디버그 로그가 실제로 출력을 남겼는지 (확인 대상이 비어 있지 않은지)
        assertTrue(output.all.contains("/realms/internal/token/refresh"), "요청 디버그 로그 없음")
        val secrets = listOf(PASSWORD, WRONG_PASSWORD, accessToken, refreshToken, rotatedToken, refreshedAccessToken)
        assertEquals(0, secrets.count { it in output.all }, "출력에 남은 비밀값 수") // 값 자체는 메시지에 쓰지 않음
    }

    private fun login(
        email: String,
        password: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/login") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))
            }.andReturn()
            .response

    private fun refresh(token: String): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/token/refresh") {
                cookie(Cookie("dozy_refresh", token))
                header("Origin", ALLOWED_ORIGIN)
            }.andReturn()
            .response

    private fun refreshCookie(response: MockHttpServletResponse): String =
        HttpCookie.parse(checkNotNull(response.getHeader("Set-Cookie"))).single().value

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        /** test 프로필의 CORS 허용 origin (`application-test.yaml`). */
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
        const val PARENT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"

        /** SEC-03 확인에서 디버그로 올리는 로거. 요청·보안 처리와 서버 코드의 로그입니다. */
        val VERBOSE_LOGGERS = listOf("com.dozycoffee.auth", "org.springframework.web", "org.springframework.security")
    }
}
