package com.dozycoffee.auth.server.adapter.inbound.web.error

import com.dozycoffee.auth.server.config.ClockConfig
import com.dozycoffee.auth.server.config.JwtConfig
import com.dozycoffee.auth.server.config.SecurityConfig
import com.dozycoffee.auth.server.config.TokenVerificationConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Spring Security 필터 단계의 401·403도 컨트롤러의 에러와 같은 형식으로 응답하는지 확인합니다. */
@WebMvcTest
@ContextConfiguration(
    classes = [
        SecurityConfig::class,
        TokenVerificationConfig::class,
        JwtConfig::class,
        ClockConfig::class,
        GlobalExceptionHandler::class,
        TraceIdFilter::class,
        ProblemAuthenticationEntryPoint::class,
        ProblemAccessDeniedHandler::class,
    ],
)
@ActiveProfiles("test")
class SecurityErrorResponseWebTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `401 인증 없이 닫힌 경로에 접근하면 UNAUTHENTICATED Problem Details`() {
        mockMvc.get("/realms/internal/me") { header("X-Trace-Id", "trace-1") }.andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
            header { string("X-Trace-Id", "trace-1") }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.type") { value("https://docs.dozycoffee.com/errors/unauthenticated") }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
            jsonPath("$.instance") { value("/realms/internal/me") }
            jsonPath("$.traceId") { value("trace-1") }
        }
    }

    @Test
    @WithMockUser
    fun `403 인증은 됐지만 닫힌 경로에 접근하면 FORBIDDEN Problem Details`() {
        mockMvc.get("/realms/internal/me") { header("X-Trace-Id", "trace-2") }.andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.code") { value("FORBIDDEN") }
            jsonPath("$.traceId") { value("trace-2") }
        }
    }
}
