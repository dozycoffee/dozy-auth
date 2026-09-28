package com.dozycoffee.auth.starter

import com.dozycoffee.auth.starter.reactivesample.ReactiveSampleApplication
import com.dozycoffee.auth.starter.support.JwksServer
import com.dozycoffee.auth.starter.support.TestTokens.EMPLOYEE_ID
import com.dozycoffee.auth.starter.support.TestTokens.FIXED_CLOCK
import com.dozycoffee.auth.starter.support.TestTokens.ISSUER_BASE
import com.dozycoffee.auth.starter.support.TestTokens.SESSION_ID
import com.dozycoffee.auth.starter.support.TestTokens.employeeClaims
import com.dozycoffee.auth.starter.support.TestTokens.sign
import org.hamcrest.Matchers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Clock

/**
 * WebFlux 서비스에 스타터를 붙였을 때의 동작 (starter.md §3~§5). 샘플 앱은 audience `wms`, realm `internal`입니다.
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
    fun `검증에 실패한 토큰은 401이고 실패 이유를 응답에 넣지 않음`() {
        val wrongAudience = sign(employeeClaims("aud" to listOf("catalog")))

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
            .isEqualTo("inbound_manager")
            .jsonPath("$.sid")
            .isEqualTo(SESSION_ID)
    }

    @Test
    fun `suspend 함수에서 자기 audience의 role이 있으면 PreAuthorize hasRole 통과`() {
        client
            .get()
            .uri("/inbounds")
            .bearer(sign(employeeClaims()))
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `suspend 함수에서 role이 없으면 403 Problem Details`() {
        client
            .get()
            .uri("/stocks/admin")
            .bearer(sign(employeeClaims()))
            .exchange()
            .expectProblem(status = 403, code = "FORBIDDEN", type = "forbidden", title = "Forbidden")
    }

    @Test
    fun `Mono를 돌려주는 메서드도 role로 인가`() {
        val token = sign(employeeClaims())

        client
            .get()
            .uri("/mono/inbounds")
            .bearer(token)
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/mono/stocks/admin")
            .bearer(token)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `다른 audience의 role은 권한으로 쓰지 않음`() {
        val onlyCatalogRole = sign(employeeClaims("roles" to listOf("catalog:inbound_manager")))

        client
            .get()
            .uri("/inbounds")
            .bearer(onlyCatalogRole)
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
    ): WebTestClient.ResponseSpec {
        expectStatus().isEqualTo(status)
        expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json"))
        expectHeader().exists("X-Trace-Id")
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
            .exists()
            .jsonPath("$.traceId")
            .value(Matchers.notNullValue())
        return this
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
            registry.add("dozy.auth.audience") { "wms" }
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
