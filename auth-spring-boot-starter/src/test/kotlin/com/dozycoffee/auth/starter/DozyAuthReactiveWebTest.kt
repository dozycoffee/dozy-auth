package com.dozycoffee.auth.starter

import com.dozycoffee.auth.starter.reactivesample.ReactiveSampleApplication
import com.dozycoffee.auth.starter.support.JwksServer
import com.dozycoffee.auth.starter.support.TestKeys
import com.dozycoffee.auth.starter.support.TestTokens.EMPLOYEE_ID
import com.dozycoffee.auth.starter.support.TestTokens.FIXED_CLOCK
import com.dozycoffee.auth.starter.support.TestTokens.ISSUER_BASE
import com.dozycoffee.auth.starter.support.TestTokens.SESSION_ID
import com.dozycoffee.auth.starter.support.TestTokens.employeeClaims
import com.dozycoffee.auth.starter.support.TestTokens.sign
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebFlux 서비스에 스타터를 붙였을 때의 동작 (starter.md §3~§5). 샘플 앱은 audience `sample`, realm `internal`입니다.
 *
 * 에러 응답의 필드 이름과 값은 api/conventions.md §4의 문자열을 그대로 기대값으로 씁니다.
 */
@SpringBootTest(classes = [ReactiveSampleApplication::class], properties = ["spring.main.web-application-type=reactive"])
@AutoConfigureWebTestClient
@Import(DozyAuthReactiveWebTest.FixedClock::class)
class DozyAuthReactiveWebTest {
    @Autowired
    lateinit var client: WebTestClient

    @Test
    fun `토큰이 없으면 401과 Bearer 인증 요구, Problem Details`() {
        client
            .get()
            .uri(
                "/me",
            ).exchange()
            .expectProblem(status = 401, code = "UNAUTHENTICATED", type = "unauthenticated", title = "Unauthenticated")
            .expectHeader()
            .valueEquals("WWW-Authenticate", "Bearer")
    }

    @Test
    fun `401 본문은 서비스의 codec으로 씀`() {
        val body =
            String(
                checkNotNull(
                    client
                        .get()
                        .uri("/me")
                        .exchange()
                        .expectBody()
                        .returnResult()
                        .responseBody,
                ),
            )

        assertTrue(body.contains("\n"), "샘플 앱의 Jackson 설정(들여쓰기)이 적용되어야 함: $body")
    }

    @Test
    fun `검증에 실패한 토큰은 401이고 실패 이유를 응답에 넣지 않음`() {
        val wrongAudience = sign(employeeClaims("aud" to listOf("other")))

        client
            .get()
            .uri("/me")
            .bearer(wrongAudience)
            .exchange()
            .expectProblem(status = 401, code = "UNAUTHENTICATED", type = "unauthenticated", title = "Unauthenticated")
            .expectBody()
            .jsonPath("$.detail")
            .doesNotExist()
    }

    @Test
    fun `서명이 틀리거나 모르는 키로 서명한 토큰도 401`() {
        val (header, _, signature) = sign(employeeClaims()).split(".")
        val forgedBody = sign(employeeClaims("roles" to listOf("sample:admin"))).split(".")[1]

        client
            .get()
            .uri("/me")
            .bearer("$header.$forgedBody.$signature")
            .exchange()
            .expectStatus()
            .isUnauthorized
        client
            .get()
            .uri("/me")
            .bearer(sign(employeeClaims(), key = TestKeys.UNKNOWN))
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `요청의 X-Trace-Id를 에러 응답의 traceId로 씀`() {
        client
            .get()
            .uri("/me")
            .header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
            .exchange()
            .expectHeader()
            .valueEquals("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
            .expectBody()
            .jsonPath("$.traceId")
            .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
    }

    @Test
    fun `X-Trace-Id 형식이 올바르지 않으면 새 값을 만듦`() {
        client
            .get()
            .uri("/me")
            .header("X-Trace-Id", "<script>alert(1)</script>")
            .exchange()
            .expectBody()
            .jsonPath("$.traceId")
            .value<String> { assertTrue(Regex("[0-9a-f]{32}").matches(it)) }
    }

    @Test
    fun `서비스 필터가 응답에 붙인 X-Trace-Id를 요청의 X-Trace-Id보다 먼저 쓰고 덮어쓰지 않음`() {
        client
            .get()
            .uri("/me")
            .header("X-Sample-Trace-Id", "filter-trace-id")
            .header("X-Trace-Id", "4bf92f3577b34da6a3ce929d0e0e4736")
            .exchange()
            .expectHeader()
            .values("X-Trace-Id") { assertEquals(listOf("filter-trace-id"), it) }
            .expectBody()
            .jsonPath("$.traceId")
            .isEqualTo("filter-trace-id")
    }

    @Test
    fun `공개 경로는 토큰 없이 호출 가능`() {
        client
            .get()
            .uri("/public/ping")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `CurrentPrincipal로 인증된 주체를 받고 role은 자기 audience 것만 prefix 없이 담김`() {
        client
            .get()
            .uri("/me")
            .bearer(sign(employeeClaims()))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.sub")
            .isEqualTo("employee:$EMPLOYEE_ID")
            .jsonPath("$.realm")
            .isEqualTo("INTERNAL")
            .jsonPath("$.roles.length()")
            .isEqualTo(1)
            .jsonPath("$.roles[0]")
            .isEqualTo("item_manager")
            .jsonPath("$.sid")
            .isEqualTo(SESSION_ID)
    }

    @Test
    fun `suspend 함수에서 자기 audience의 role이 있으면 PreAuthorize hasRole 통과`() {
        client
            .get()
            .uri("/items")
            .bearer(sign(employeeClaims()))
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `suspend 함수에서 role이 없으면 403 Problem Details`() {
        client
            .get()
            .uri("/items/admin")
            .bearer(sign(employeeClaims()))
            .exchange()
            .expectProblem(status = 403, code = "FORBIDDEN", type = "forbidden", title = "Forbidden", instance = "/items/admin")
    }

    @Test
    fun `Mono를 돌려주는 메서드도 role로 인가`() {
        val token = sign(employeeClaims())

        client
            .get()
            .uri("/mono/items")
            .bearer(token)
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/mono/items/admin")
            .bearer(token)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `다른 audience의 role은 권한으로 쓰지 않음`() {
        val onlyOtherAudienceRole = sign(employeeClaims("roles" to listOf("other:item_manager")))

        client
            .get()
            .uri("/items")
            .bearer(onlyOtherAudienceRole)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `dozyAuth isType은 principal type이 같을 때만 통과`() {
        val token = sign(employeeClaims())

        client
            .get()
            .uri("/employees-only")
            .bearer(token)
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/partners-only")
            .bearer(token)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    private fun WebTestClient.RequestHeadersSpec<*>.bearer(token: String) = header("Authorization", "Bearer $token")

    private fun WebTestClient.ResponseSpec.expectProblem(
        status: Int,
        code: String,
        type: String,
        title: String,
        instance: String = "/me",
    ): WebTestClient.ResponseSpec {
        expectStatus().isEqualTo(status)
        expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json"))
        expectBody()
            .jsonPath("$.type")
            .isEqualTo("https://docs.dozycoffee.com/errors/$type")
            .jsonPath("$.title")
            .isEqualTo(title)
            .jsonPath("$.status")
            .isEqualTo(status)
            .jsonPath("$.code")
            .isEqualTo(code)
            .jsonPath("$.instance")
            .isEqualTo(instance)
        val result = expectBody().returnResult()
        val body = JsonMapper().readTree(result.responseBody)
        assertEquals(setOf("type", "title", "status", "instance", "code", "traceId"), body.propertyNames().toSet())
        assertEquals(listOf(body["traceId"].asString()), result.responseHeaders.get("X-Trace-Id"))
        return this
    }

    /** 서비스가 401·403 핸들러 빈을 직접 정의한 경우 (starter.md §3). */
    @Nested
    @Import(ServiceHandlers::class)
    inner class ServiceDefinedHandlers {
        @Autowired
        lateinit var context: ApplicationContext

        @Test
        fun `서비스가 ServerAuthenticationEntryPoint를 정의하면 스타터 것은 빠지고 401에 서비스 것을 씀`() {
            assertEquals(setOf("serviceEntryPoint"), context.getBeansOfType(ServerAuthenticationEntryPoint::class.java).keys)

            client
                .get()
                .uri("/me")
                .exchange()
                .expectStatus()
                .isUnauthorized
                .expectHeader()
                .valueEquals("X-Handler", "service-entry-point")
        }

        @Test
        fun `서비스가 ServerAccessDeniedHandler를 정의하면 스타터 것은 빠지고 403에 서비스 것을 씀`() {
            assertEquals(setOf("serviceAccessDeniedHandler"), context.getBeansOfType(ServerAccessDeniedHandler::class.java).keys)

            client
                .get()
                .uri("/items/admin")
                .bearer(sign(employeeClaims()))
                .exchange()
                .expectStatus()
                .isForbidden
                .expectHeader()
                .valueEquals("X-Handler", "service-access-denied-handler")
        }
    }

    @TestConfiguration
    class ServiceHandlers {
        @Bean
        fun serviceEntryPoint() =
            ServerAuthenticationEntryPoint { exchange, _ ->
                Mono.fromRunnable {
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.headers.set("X-Handler", "service-entry-point")
                }
            }

        @Bean
        fun serviceAccessDeniedHandler() =
            ServerAccessDeniedHandler { exchange, _ ->
                Mono.fromRunnable {
                    exchange.response.statusCode = HttpStatus.FORBIDDEN
                    exchange.response.headers.set("X-Handler", "service-access-denied-handler")
                }
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
