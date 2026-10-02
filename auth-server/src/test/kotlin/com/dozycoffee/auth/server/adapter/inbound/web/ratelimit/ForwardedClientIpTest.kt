package com.dozycoffee.auth.server.adapter.inbound.web.ratelimit

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.support.MutableClockConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 프록시 뒤의 클라이언트 주소 (configuration.md §9). 실제 Tomcat을 띄워 `X-Forwarded-For` 처리를 확인합니다.
 *
 * 테스트 요청은 `127.0.0.1`에서 오며, 기본 설정은 루프백을 신뢰할 프록시로 봅니다. 이 클래스는 신뢰할 프록시를 루프백이
 * 아닌 주소로 바꿔, 신뢰하지 않는 곳에서 온 `X-Forwarded-For`를 무시하는지 확인합니다. 신뢰하는 경우는 [TrustedProxyClientIpTest]입니다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["server.tomcat.remoteip.internal-proxies=10.255.255.1/32"],
)
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class)
@ActiveProfiles("test")
class ForwardedClientIpTest {
    @LocalServerPort
    var port: Int = 0

    @Test
    fun `신뢰하지 않는 곳에서 온 X-Forwarded-For는 무시하고 연결한 주소로 셈`() {
        repeat(AuthPolicy.RATE_LIMIT_IP.capacity) { index -> login(port, forwardedFor = "198.51.100.${index + 1}") }

        assertEquals(429, login(port, forwardedFor = "198.51.100.200").statusCode())
    }
}

/** 신뢰할 프록시(기본 설정: 사설·루프백 대역)에서 온 요청은 `X-Forwarded-For`의 클라이언트 주소로 셉니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class)
@ActiveProfiles("test")
class TrustedProxyClientIpTest {
    @LocalServerPort
    var port: Int = 0

    @Test
    fun `신뢰할 프록시가 전달한 클라이언트 주소마다 따로 셈`() {
        repeat(AuthPolicy.RATE_LIMIT_IP.capacity) { login(port, forwardedFor = CLIENT) }

        assertEquals(429, login(port, forwardedFor = CLIENT).statusCode())
        assertNotEquals(429, login(port, forwardedFor = OTHER_CLIENT).statusCode())
    }

    @Test
    fun `클라이언트가 보낸 X-Forwarded-For 앞부분은 믿지 않고 프록시가 붙인 주소로 셈`() {
        repeat(AuthPolicy.RATE_LIMIT_IP.capacity) { index -> login(port, forwardedFor = "192.0.2.${index + 1}, $CLIENT") }

        assertEquals(429, login(port, forwardedFor = "192.0.2.200, $CLIENT").statusCode())
    }

    private companion object {
        const val CLIENT = "198.51.100.1"
        const val OTHER_CLIENT = "198.51.100.2"
    }
}

private val httpClient: HttpClient = HttpClient.newHttpClient()

private fun login(
    port: Int,
    forwardedFor: String,
): HttpResponse<String> =
    httpClient.send(
        HttpRequest
            .newBuilder(URI.create("http://localhost:$port/realms/internal/login"))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", forwardedFor)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
