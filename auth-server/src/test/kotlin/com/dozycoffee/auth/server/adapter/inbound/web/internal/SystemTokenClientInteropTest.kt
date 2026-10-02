package com.dozycoffee.auth.server.adapter.inbound.web.internal

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.SystemClientTable
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.SecretHash
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 서비스 토큰 발급 API가 스타터의 system token 클라이언트와 같은 구현(Spring Security OAuth2 Client의 client credentials,
 * `client_secret_basic`)과 실제 HTTP로 맞물리는지 확인합니다 (api/internal.md 서비스 토큰 발급, starter.md §6).
 *
 * 이 클라이언트는 client_id와 secret을 URL 인코딩해서 Basic 헤더로 보냅니다 (RFC 6749 §2.3.1).
 * 서버가 다른 스레드에서 요청을 처리하므로 데이터를 커밋하고, 테스트가 끝나면 지웁니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SystemTokenClientInteropTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val created = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        inTransaction {
            created.forEach { id ->
                SystemClientTable.deleteWhere { principalId eq id }
                PrincipalTable.deleteWhere { PrincipalTable.id eq id }
            }
        }
    }

    @Test
    fun `OAuth2 Client의 client_secret_basic 요청으로 system token을 받음`() {
        val secret = OpaqueSecret.generate().value
        val clientId = registerClient(secret)

        val response = tokenClient().getTokenResponse(OAuth2ClientCredentialsGrantRequest(registration(clientId, secret)))

        val token = response.accessToken
        assertEquals(OAuth2AccessToken.TokenType.BEARER, token.tokenType)
        assertTrue(token.tokenValue.isNotEmpty())
        assertEquals(AuthPolicy.ACCESS_TOKEN_TTL, Duration.between(token.issuedAt, token.expiresAt))
        assertNull(response.refreshToken)
    }

    @Test
    fun `URL 인코딩이 필요한 문자가 있는 secret도 OAuth2 Client 요청으로 인증`() {
        val secret = "a+b/c:d%e f"
        val clientId = registerClient(secret)

        val response = tokenClient().getTokenResponse(OAuth2ClientCredentialsGrantRequest(registration(clientId, secret)))

        assertTrue(response.accessToken.tokenValue.isNotEmpty())
    }

    @Test
    fun `secret이 틀리면 OAuth2 Client 요청이 실패`() {
        val clientId = registerClient(OpaqueSecret.generate().value)

        assertFailsWith<OAuth2AuthorizationException> {
            tokenClient().getTokenResponse(OAuth2ClientCredentialsGrantRequest(registration(clientId, "wrong-secret")))
        }
    }

    private fun tokenClient() = RestClientClientCredentialsTokenResponseClient()

    private fun registration(
        clientId: String,
        secret: String,
    ): ClientRegistration =
        ClientRegistration
            .withRegistrationId("dozy-auth")
            .clientId(clientId)
            .clientSecret(secret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri("http://localhost:$port/realms/internal/token")
            .build()

    private fun registerClient(secret: String): String {
        val clientId = "svc-interop-${UUID.randomUUID().toString().replace("-", "").take(SUFFIX_LENGTH)}"
        inTransaction {
            val principalId =
                PrincipalTable
                    .insertReturning(listOf(PrincipalTable.id)) {
                        it[type] = "SYSTEM"
                        it[status] = "ACTIVE"
                    }.single()[PrincipalTable.id]
            created += principalId
            SystemClientTable.insert {
                it[SystemClientTable.principalId] = principalId
                it[SystemClientTable.clientId] = clientId
                it[clientSecretHash] = SecretHash.of(secret).hex
                it[name] = "연동 테스트"
                it[secretRotatedAt] = AT
            }
        }
        return clientId
    }

    private fun <T> inTransaction(block: () -> T): T? = TransactionTemplate(transactionManager).execute { block() }

    private companion object {
        const val SUFFIX_LENGTH = 12
        val AT: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
