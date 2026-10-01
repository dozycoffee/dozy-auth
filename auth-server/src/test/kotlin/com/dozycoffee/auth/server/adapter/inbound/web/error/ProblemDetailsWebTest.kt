package com.dozycoffee.auth.server.adapter.inbound.web.error

import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import com.dozycoffee.auth.server.domain.credential.InvalidCredentialsException
import com.dozycoffee.auth.server.domain.session.TokenRotatedException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import kotlin.test.assertTrue

/**
 * 컨트롤러가 던진 예외를 Problem Details로 바꾸는지 상태 코드별로 확인합니다 (api/conventions.md §4, §5).
 * 에러 응답의 모양(`code`, `traceId`)과 계약 값은 명세의 문자열 그대로 기대값으로 씁니다.
 */
@WebMvcTest
@ContextConfiguration(
    classes = [
        ProblemDetailsWebTest.ThrowingController::class,
        ProblemDetailsWebTest.OpenSecurityConfig::class,
        GlobalExceptionHandler::class,
        TraceIdFilter::class,
        ProblemAuthenticationEntryPoint::class,
        ProblemAccessDeniedHandler::class,
    ],
)
class ProblemDetailsWebTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `400 요청 본문이 검증을 통과하지 못하면 VALIDATION_FAILED와 필드 목록`() {
        mockMvc
            .post("/test/validated") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": ""}"""
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith("application/problem+json") }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
                jsonPath("$.status") { value(400) }
                jsonPath("$.errors[0].field") { value("name") }
                jsonPath("$.errors[0].code") { value("NotBlank") }
                jsonPath("$.errors[0].message") { exists() }
            }
    }

    @Test
    fun `400 JSON 형식이 깨지면 VALIDATION_FAILED`() {
        mockMvc
            .post("/test/validated") {
                contentType = MediaType.APPLICATION_JSON
                content = "{broken"
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
            }
    }

    @Test
    fun `401 도메인 예외는 INVALID_CREDENTIALS와 WWW-Authenticate 헤더`() {
        mockMvc.get("/test/invalid-credentials").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
            jsonPath("$.code") { value("INVALID_CREDENTIALS") }
            jsonPath("$.type") { value("https://docs.dozycoffee.com/errors/invalid-credentials") }
            jsonPath("$.title") { value("Invalid credentials") }
            jsonPath("$.instance") { value("/test/invalid-credentials") }
        }
    }

    @Test
    fun `401 인증 예외는 UNAUTHENTICATED`() {
        mockMvc.get("/test/unauthenticated").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
            content { string(not(containsString("secret detail"))) }
        }
    }

    @Test
    fun `401 로그인하지 않은 요청이 인가에서 거부되면 FORBIDDEN이 아니라 UNAUTHENTICATED`() {
        mockMvc.get("/test/forbidden").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    @WithMockUser
    fun `403 로그인한 사용자가 인가에서 거부되면 FORBIDDEN`() {
        mockMvc.get("/test/forbidden").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
            jsonPath("$.status") { value(403) }
        }
    }

    @Test
    fun `404 없는 경로는 NOT_FOUND`() {
        mockMvc.get("/test/missing").andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `405 허용하지 않는 메서드는 METHOD_NOT_ALLOWED`() {
        mockMvc.post("/test/invalid-credentials").andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.code") { value("METHOD_NOT_ALLOWED") }
        }
    }

    @Test
    fun `409 도메인 예외는 TOKEN_ROTATED`() {
        mockMvc.get("/test/token-rotated").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("TOKEN_ROTATED") }
        }
    }

    @Test
    fun `415 지원하지 않는 본문 형식은 UNSUPPORTED_MEDIA_TYPE`() {
        mockMvc
            .post("/test/validated") {
                contentType = MediaType.TEXT_PLAIN
                content = "name"
            }.andExpect {
                status { isUnsupportedMediaType() }
                jsonPath("$.code") { value("UNSUPPORTED_MEDIA_TYPE") }
            }
    }

    @Test
    fun `429 요청 제한은 TOO_MANY_ATTEMPTS와 Retry-After 헤더`() {
        mockMvc.get("/test/too-many-attempts").andExpect {
            status { isTooManyRequests() }
            header { string("Retry-After", "90") }
            jsonPath("$.code") { value("TOO_MANY_ATTEMPTS") }
        }
    }

    @Test
    fun `500 처리하지 못한 예외는 내부 메시지를 노출하지 않음`() {
        mockMvc.get("/test/unexpected").andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            content { string(not(containsString("jdbc"))) }
            content { string(not(containsString("IllegalStateException"))) }
        }
    }

    @Test
    fun `요청의 X-Trace-Id를 응답 헤더와 에러 본문의 traceId로 씀`() {
        mockMvc
            .get("/test/token-rotated") { header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736") }
            .andExpect {
                header { string("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736") }
                jsonPath("$.traceId") { value("4bf92f3577b34da6a3ce929d0e0e4736") }
            }
    }

    @Test
    fun `X-Trace-Id가 없거나 형식이 틀리면 새로 만들어 헤더와 본문에 같은 값을 씀`() {
        for (incoming in listOf(null, "bad value!", "a".repeat(65))) {
            val result =
                mockMvc
                    .get("/test/token-rotated") { incoming?.let { header("X-Trace-Id", it) } }
                    .andExpect { jsonPath("$.traceId") { value(matchesPattern("[0-9a-f]{32}")) } }
                    .andReturn()
            val body = result.response.contentAsString
            val header = result.response.getHeader("X-Trace-Id")
            assertTrue(body.contains("\"traceId\":\"$header\""), "헤더 $header 가 본문에 없음: $body")
        }
    }

    @RestController
    class ThrowingController {
        @GetMapping("/test/invalid-credentials")
        fun invalidCredentials(): Unit = throw InvalidCredentialsException()

        @GetMapping("/test/token-rotated")
        fun tokenRotated(): Unit = throw TokenRotatedException()

        @GetMapping("/test/too-many-attempts")
        fun tooManyAttempts(): Unit = throw TooManyAttemptsException(Duration.ofSeconds(90))

        @GetMapping("/test/unauthenticated")
        fun unauthenticated(): Unit = throw BadCredentialsException("secret detail")

        @GetMapping("/test/forbidden")
        fun forbidden(): Unit = throw AccessDeniedException("secret detail")

        @GetMapping("/test/unexpected")
        fun unexpected(): Unit = throw IllegalStateException("jdbc:postgresql://db:5432/auth 연결 실패")

        @PostMapping("/test/validated", consumes = [MediaType.APPLICATION_JSON_VALUE])
        fun validated(
            @Valid @RequestBody body: Body,
        ) = Unit
    }

    data class Body(
        @field:NotBlank val name: String?,
    )

    /** 컨트롤러 예외 테스트용. 인가는 열어 두고 401·403 처리기만 운영과 같은 것을 씁니다. */
    @TestConfiguration
    class OpenSecurityConfig {
        @Bean
        fun chain(
            http: HttpSecurity,
            entryPoint: ProblemAuthenticationEntryPoint,
            accessDeniedHandler: ProblemAccessDeniedHandler,
        ): SecurityFilterChain {
            http {
                authorizeHttpRequests { authorize(anyRequest, permitAll) }
                csrf { disable() }
                exceptionHandling {
                    authenticationEntryPoint = entryPoint
                    this.accessDeniedHandler = accessDeniedHandler
                }
            }
            return http.build()
        }
    }
}
