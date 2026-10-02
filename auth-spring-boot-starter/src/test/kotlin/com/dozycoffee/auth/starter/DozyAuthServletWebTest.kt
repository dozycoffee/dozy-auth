package com.dozycoffee.auth.starter

import com.dozycoffee.auth.starter.sample.SampleApplication
import com.dozycoffee.auth.starter.support.JwksServer
import com.dozycoffee.auth.starter.support.TestKeys
import com.dozycoffee.auth.starter.support.TestTokens.EMPLOYEE_ID
import com.dozycoffee.auth.starter.support.TestTokens.FIXED_CLOCK
import com.dozycoffee.auth.starter.support.TestTokens.ISSUER_BASE
import com.dozycoffee.auth.starter.support.TestTokens.SESSION_ID
import com.dozycoffee.auth.starter.support.TestTokens.employeeClaims
import com.dozycoffee.auth.starter.support.TestTokens.sign
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 서비스에 스타터를 붙였을 때의 동작 (starter.md §3~§5). 샘플 앱은 audience `sample`, realm `internal`입니다.
 *
 * 에러 응답의 필드 이름과 값은 api/conventions.md §4의 문자열을 그대로 기대값으로 씁니다.
 */
@SpringBootTest(classes = [SampleApplication::class])
@AutoConfigureMockMvc
@Import(DozyAuthServletWebTest.FixedClock::class)
class DozyAuthServletWebTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `토큰이 없으면 401과 Bearer 인증 요구, Problem Details`() {
        mockMvc.get("/me").andExpectProblem(status = 401, code = "UNAUTHENTICATED", type = "unauthenticated", title = "Unauthenticated") {
            header { string("WWW-Authenticate", "Bearer") }
        }
    }

    @Test
    fun `401 본문은 서비스의 메시지 변환기로 씀`() {
        val body =
            mockMvc
                .get("/me")
                .andReturn()
                .response.contentAsString

        assertTrue(body.contains("\n"), "샘플 앱의 Jackson 설정(들여쓰기)이 적용되어야 함: $body")
    }

    @Test
    fun `검증에 실패한 토큰은 401이고 실패 이유를 응답에 넣지 않음`() {
        val wrongAudience = sign(employeeClaims("aud" to listOf("other")))

        mockMvc
            .get("/me") {
                bearer(wrongAudience)
            }.andExpectProblem(status = 401, code = "UNAUTHENTICATED", type = "unauthenticated", title = "Unauthenticated") {
                jsonPath("$.detail") { doesNotExist() }
            }
    }

    @Test
    fun `서명이 틀리거나 모르는 키로 서명한 토큰도 401`() {
        val (header, _, signature) = sign(employeeClaims()).split(".")
        val forgedBody = sign(employeeClaims("roles" to listOf("sample:admin"))).split(".")[1]

        mockMvc.get("/me") { bearer("$header.$forgedBody.$signature") }.andExpect { status { isUnauthorized() } }
        mockMvc.get("/me") { bearer(sign(employeeClaims(), key = TestKeys.UNKNOWN)) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `요청의 X-Trace-Id를 에러 응답의 traceId로 씀`() {
        mockMvc.get("/me") { header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736") }.andExpect {
            header { string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736") }
            jsonPath("$.traceId") { value("4bf92f3577b34da6a3ce929d0e0e4736") }
        }
    }

    @Test
    fun `X-Trace-Id 형식이 올바르지 않으면 새 값을 만듦`() {
        mockMvc.get("/me") { header("X-Trace-Id", "<script>alert(1)</script>") }.andExpect {
            jsonPath("$.traceId") { value(Matchers.matchesPattern("[0-9a-f]{32}")) }
        }
    }

    @Test
    fun `서비스 필터가 응답에 붙인 X-Trace-Id를 요청의 X-Trace-Id보다 먼저 쓰고 덮어쓰지 않음`() {
        mockMvc
            .get("/me") {
                header("X-Sample-Trace-Id", "filter-trace-id")
                header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
            }.andExpect {
                header { stringValues("X-Trace-Id", "filter-trace-id") }
                jsonPath("$.traceId") { value("filter-trace-id") }
            }
    }

    @Test
    fun `공개 경로는 토큰 없이 호출 가능`() {
        mockMvc.get("/public/ping").andExpect { status { isOk() } }
    }

    @Test
    fun `CurrentPrincipal로 인증된 주체를 받고 role은 자기 audience 것만 prefix 없이 담김`() {
        mockMvc.get("/me") { bearer(sign(employeeClaims())) }.andExpect {
            status { isOk() }
            jsonPath("$.sub") { value("employee:$EMPLOYEE_ID") }
            jsonPath("$.realm") { value("INTERNAL") }
            jsonPath("$.roles.length()") { value(1) }
            jsonPath("$.roles[0]") { value("item_manager") }
            jsonPath("$.sid") { value(SESSION_ID) }
        }
    }

    @Test
    fun `자기 audience의 role이 있으면 PreAuthorize hasRole 통과`() {
        mockMvc.get("/items") { bearer(sign(employeeClaims())) }.andExpect { status { isOk() } }
    }

    @Test
    fun `role이 없으면 403 Problem Details`() {
        mockMvc
            .get("/items/admin") { bearer(sign(employeeClaims())) }
            .andExpectProblem(status = 403, code = "FORBIDDEN", type = "forbidden", title = "Forbidden", instance = "/items/admin")
    }

    @Test
    fun `다른 audience의 role은 권한으로 쓰지 않음`() {
        val onlyOtherAudienceRole = sign(employeeClaims("roles" to listOf("other:item_manager")))

        mockMvc.get("/items") { bearer(onlyOtherAudienceRole) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `dozyAuth isType은 principal type이 같을 때만 통과`() {
        val token = sign(employeeClaims())

        mockMvc.get("/employees-only") { bearer(token) }.andExpect { status { isOk() } }
        mockMvc.get("/partners-only") { bearer(token) }.andExpect { status { isForbidden() } }
    }

    private fun MockHttpServletRequestDsl.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private fun ResultActionsDsl.andExpectProblem(
        status: Int,
        code: String,
        type: String,
        title: String,
        instance: String = "/me",
        more: MockMvcResultMatchersDsl.() -> Unit = {},
    ): ResultActionsDsl {
        andExpect {
            status { isEqualTo(status) }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.type") { value("https://docs.dozycoffee.com/errors/$type") }
            jsonPath("$.title") { value(title) }
            jsonPath("$.status") { value(status) }
            jsonPath("$.code") { value(code) }
            jsonPath("$.instance") { value(instance) }
            more()
        }
        val response = andReturn().response
        val body = JsonMapper().readTree(response.contentAsByteArray)
        assertEquals(setOf("type", "title", "status", "instance", "code", "traceId"), body.propertyNames().toSet())
        assertEquals(listOf(body["traceId"].asString()), response.getHeaders("X-Trace-Id"))
        return this
    }

    /** 서비스가 401·403 핸들러 빈을 직접 정의한 경우 (starter.md §3). */
    @Nested
    @Import(ServiceHandlers::class)
    inner class ServiceDefinedHandlers {
        @Autowired
        lateinit var context: ApplicationContext

        @Test
        fun `서비스가 AuthenticationEntryPoint를 정의하면 스타터 것은 빠지고 401에 서비스 것을 씀`() {
            assertTrue(context.getBeansOfType(AuthenticationEntryPoint::class.java).keys == setOf("serviceEntryPoint"))

            mockMvc.get("/me").andExpect {
                status { isUnauthorized() }
                header { string("X-Handler", "service-entry-point") }
            }
        }

        @Test
        fun `서비스가 AccessDeniedHandler를 정의하면 스타터 것은 빠지고 403에 서비스 것을 씀`() {
            assertTrue(context.getBeansOfType(AccessDeniedHandler::class.java).keys == setOf("serviceAccessDeniedHandler"))

            mockMvc.get("/items/admin") { bearer(sign(employeeClaims())) }.andExpect {
                status { isForbidden() }
                header { string("X-Handler", "service-access-denied-handler") }
            }
        }
    }

    @TestConfiguration
    class ServiceHandlers {
        @Bean
        fun serviceEntryPoint() =
            AuthenticationEntryPoint { _, response, _ ->
                response.status = 401
                response.setHeader("X-Handler", "service-entry-point")
            }

        @Bean
        fun serviceAccessDeniedHandler() =
            AccessDeniedHandler { _, response, _ ->
                response.status = 403
                response.setHeader("X-Handler", "service-access-denied-handler")
            }
    }

    @TestConfiguration
    class FixedClock {
        @Bean
        fun clock(): Clock = FIXED_CLOCK
    }

    companion object {
        private val jwks = JwksServer()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("dozy.auth.audience") { "sample" }
            registry.add("dozy.auth.accepted-realms") { "internal" }
            registry.add("dozy.auth.issuer-base-uri") { ISSUER_BASE }
            registry.add("dozy.auth.jwk-set-uri") { jwks.jwkSetUri }
            registry.add("dozy.auth.public-paths") { "/public/**" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = jwks.close()
    }
}
