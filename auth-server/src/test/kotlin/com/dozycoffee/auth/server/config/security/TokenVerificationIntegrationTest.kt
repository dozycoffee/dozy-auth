package com.dozycoffee.auth.server.config.security

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAccessDeniedHandler
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAuthenticationEntryPoint
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import com.dozycoffee.auth.starter.DozyAuthServletAutoConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auth 서버의 토큰 검증에 스타터를 쓰는 구성 (ADR-0031, api/conventions.md §2, §4, §7).
 *
 * - 검증은 스타터 디코더, 401·403은 서버 처리기가 맡는지
 * - 경로별 필터 체인의 `aud`·principal type 검사
 * - 서비스(스타터)와 서버의 401·403 본문 필드가 같은지
 *
 * 관리·내부 API 엔드포인트는 아직 없으므로 인증·인가를 통과하면 `404`입니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class)
@ActiveProfiles("test")
class TokenVerificationIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var employees: TestEmployees

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    @Autowired
    lateinit var tokens: TestAccessTokens

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `스타터의 기본 디코더, 필터 체인, 401·403 처리기는 만들지 않고 서버 것을 씀`() {
        assertEquals(
            setOf(TokenVerificationConfig.AUTH_AUDIENCE_DECODER, TokenVerificationConfig.USER_DECODER),
            context.getBeanNamesForType(JwtDecoder::class.java).toSet(),
        )
        assertFalse(context.containsBean("dozySecurityFilterChain"))
        assertEquals(6, context.getBeanNamesForType(SecurityFilterChain::class.java).size)
        assertEquals<List<Class<*>>>(
            listOf(ProblemAuthenticationEntryPoint::class.java),
            context.getBeansOfType(AuthenticationEntryPoint::class.java).values.map { it.javaClass },
        )
        assertEquals<List<Class<*>>>(
            listOf(ProblemAccessDeniedHandler::class.java),
            context.getBeansOfType(AccessDeniedHandler::class.java).values.map { it.javaClass },
        )
    }

    @Test
    fun `관리 API는 aud에 auth가 있는 직원 토큰만 통과`() {
        val admin = employees.create()

        get("/admin/employees", tokens.issue(admin.key, roles = listOf("auth:admin"))).andExpect { status { isOk() } }
    }

    @Test
    fun `관리 API는 aud에 auth가 없는 토큰을 401로 거부`() {
        val employee = employees.create()

        get("/admin/employees", tokens.issue(employee.key, roles = listOf("wms:inbound_manager"))).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `관리 API에 system token을 쓰면 403`() {
        get("/admin/employees", tokens.issue(SYSTEM, roles = listOf("auth:partner_reader"))).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `내부 API는 aud에 auth가 있는 system token만 통과`() {
        val admin = employees.create()

        get("/internal/partners/x", tokens.issue(SYSTEM, roles = listOf("auth:partner_reader"))).andExpect { status { isNotFound() } }
        get("/internal/partners/x", tokens.issue(admin.key, roles = listOf("auth:admin"))).andExpect { status { isForbidden() } }
    }

    @Test
    fun `어느 체인에도 없는 경로는 인증 없이 401, 토큰이 있어도 열지 않음`() {
        mockMvc.get("/unknown").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `401 본문의 필드는 서비스(스타터)와 서버가 같음`() {
        val server = mockMvc.get("/realms/internal/me").andReturn().response
        val service =
            starterResponse {
                entryPoint,
                _,
                request,
                response,
                ->
                entryPoint.commence(request, response, BadCredentialsException("x"))
            }

        assertSameProblem(service, server)
    }

    @Test
    fun `403 본문의 필드는 서비스(스타터)와 서버가 같음`() {
        val server = get("/realms/internal/me", tokens.issue(SYSTEM, roles = listOf("auth:partner_reader"))).andReturn().response
        val service = starterResponse { _, handler, request, response -> handler.handle(request, response, AccessDeniedException("x")) }

        assertSameProblem(service, server)
    }

    @Test
    fun `CORS 허용 origin의 preflight는 그 origin과 credentials를 허용`() {
        mockMvc
            .options("/realms/internal/login") {
                header("Origin", "https://admin.dozycoffee.test")
                header("Access-Control-Request-Method", "POST")
                header("Access-Control-Request-Headers", "Content-Type")
            }.andExpect {
                status { isOk() }
                header { string("Access-Control-Allow-Origin", "https://admin.dozycoffee.test") }
                header { string("Access-Control-Allow-Credentials", "true") }
            }
    }

    @Test
    fun `CORS 허용하지 않은 origin의 preflight는 거부`() {
        mockMvc
            .options("/realms/internal/me") {
                header("Origin", "https://evil.example")
                header("Access-Control-Request-Method", "GET")
            }.andExpect {
                status { isForbidden() }
                header { doesNotExist("Access-Control-Allow-Origin") }
            }
    }

    private fun get(
        path: String,
        accessToken: String,
    ): ResultActionsDsl = mockMvc.get(path) { header("Authorization", "Bearer $accessToken") }

    /** 스타터만 붙인 서비스의 401·403 처리기로 응답을 만듭니다. */
    private fun starterResponse(
        write: (AuthenticationEntryPoint, AccessDeniedHandler, MockHttpServletRequest, MockHttpServletResponse) -> Unit,
    ): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DozyAuthServletAutoConfiguration::class.java,
                    SecurityAutoConfiguration::class.java,
                    ServletWebSecurityAutoConfiguration::class.java,
                    WebMvcAutoConfiguration::class.java,
                    HttpMessageConvertersAutoConfiguration::class.java,
                    JacksonAutoConfiguration::class.java,
                ),
            ).withPropertyValues(
                "dozy.auth.audience=wms",
                "dozy.auth.accepted-realms=internal",
                "dozy.auth.issuer-base-uri=http://localhost:8080",
            ).run { service ->
                val request = MockHttpServletRequest("GET", "/realms/internal/me")
                write(
                    service.getBean(AuthenticationEntryPoint::class.java),
                    service.getBean(AccessDeniedHandler::class.java),
                    request,
                    response,
                )
            }
        return response
    }

    /** api/conventions.md §4의 필수 필드는 둘 다 같은 값이고, 서버에만 있는 필드는 선택 필드(`detail`)뿐입니다. */
    private fun assertSameProblem(
        service: MockHttpServletResponse,
        server: MockHttpServletResponse,
    ) {
        assertEquals(service.status, server.status)
        assertTrue(service.contentType.orEmpty().startsWith("application/problem+json"), service.contentType)
        assertTrue(server.contentType.orEmpty().startsWith("application/problem+json"), server.contentType)

        val serviceBody = json(service)
        val serverBody = json(server)
        assertEquals(setOf("type", "title", "status", "instance", "code", "traceId"), serviceBody.keys)
        assertEquals(setOf("detail"), serverBody.keys - serviceBody.keys)
        for (field in listOf("type", "title", "status", "instance", "code")) {
            assertEquals(serviceBody[field], serverBody[field], field)
        }
        assertEquals(service.getHeader("WWW-Authenticate"), server.getHeader("WWW-Authenticate"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>
}
