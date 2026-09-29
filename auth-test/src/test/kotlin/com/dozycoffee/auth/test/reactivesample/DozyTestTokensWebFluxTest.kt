package com.dozycoffee.auth.test.reactivesample

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.test.DozyTestTokens
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

/** WebFlux 통합 테스트에서 `DozyTestTokens` (starter.md §7.2). 토큰이 스타터의 실제 검증 체인을 거칩니다. */
@SpringBootTest(properties = ["spring.main.web-application-type=reactive"])
@AutoConfigureWebTestClient
class DozyTestTokensWebFluxTest {
    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var tokens: DozyTestTokens

    @Test
    fun `테스트 토큰은 스타터 검증을 통과하고 role로 인가됨`() {
        call("/items", tokens.issue(roles = listOf("sample:item_manager"))).expectStatus().isOk
    }

    @Test
    fun `role이 없는 테스트 토큰은 403`() {
        call("/items", tokens.issue()).expectStatus().isForbidden
    }

    @Test
    fun `이 서비스가 받지 않는 realm의 토큰은 401`() {
        call("/me", tokens.issue(type = PrincipalType.PARTNER)).expectStatus().isUnauthorized
    }

    @Test
    fun `만료된 토큰은 401`() {
        val issuedAt = Instant.now().minusSeconds(3600)

        call("/me", tokens.issue(issuedAt = issuedAt)).expectStatus().isUnauthorized
    }

    @Test
    fun `다른 audience 토큰은 401`() {
        call("/me", tokens.issue(audience = listOf("other"))).expectStatus().isUnauthorized
    }

    @Test
    fun `DOM-01 realm과 principal type이 맞지 않는 토큰은 401`() {
        call("/me", tokens.issue(type = PrincipalType.PARTNER, realm = Realm.INTERNAL)).expectStatus().isUnauthorized
    }

    @Test
    fun `믿지 않는 키로 서명한 토큰은 401`() {
        call("/me", tokens.issue(signedBy = DozyTestTokens.Key.UNTRUSTED)).expectStatus().isUnauthorized
    }

    @Test
    fun `다른 Auth 주소의 issuer 토큰은 401`() {
        call("/me", tokens.issue(issuerBaseUri = "https://evil.example.com")).expectStatus().isUnauthorized
    }

    private fun call(
        path: String,
        token: String,
    ) = client
        .get()
        .uri(path)
        .header("Authorization", "Bearer $token")
        .exchange()
}
