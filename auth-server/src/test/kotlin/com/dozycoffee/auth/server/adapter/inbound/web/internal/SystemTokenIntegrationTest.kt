package com.dozycoffee.auth.server.adapter.inbound.web.internal

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.RolePersistenceAdapter
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import com.dozycoffee.auth.starter.DozyAuthProperties
import com.dozycoffee.auth.starter.DozyJwtDecoders
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 서비스 토큰 발급 API (api/internal.md 서비스 토큰 발급, token.md §4, §8). CLI-02, CLI-03, CLI-05, ACC-04.
 *
 * 발급한 토큰은 서비스와 같은 스타터 디코더([DozyJwtDecoders])로 검증합니다. 공개키는 JWKS API로 받은 것을 씁니다 (token.md §6의 2~9).
 * 요청마다 남기는 발급 로그(client_id, 요청 IP, 결과)에 secret, 토큰, `Authorization` 헤더가 없는지도 확인합니다 (SEC-03).
 * 요청은 MockMvc로 테스트와 같은 스레드에서 처리되어 테스트 트랜잭션에 참여하므로, 넣은 데이터는 테스트가 끝나면 되돌립니다.
 * 다른 테스트와 겹치지 않도록 client_id와 role code는 테스트마다 새로 만듭니다.
 * OAuth 에러 이름과 헤더 값은 명세(RFC 6749)의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@Transactional
@ExtendWith(OutputCaptureExtension::class)
class SystemTokenIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var roles: RolePersistenceAdapter

    @Autowired
    lateinit var issuerBaseUri: IssuerBaseUri

    @Autowired
    lateinit var clock: Clock

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `발급한 system token은 부여된 role의 audience로 JWKS 검증을 통과하고 sid가 없음`() {
        val client = registerClient(roleAudiences = listOf("auth", "catalog"))

        val body =
            requestToken(basic(client.clientId, client.secret))
                .andExpect {
                    status { isOk() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                    header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
                    header { string(HttpHeaders.PRAGMA, "no-cache") }
                    jsonPath("$.token_type") { value("Bearer") }
                    jsonPath("$.expires_in") { value(AuthPolicy.ACCESS_TOKEN_TTL.seconds) }
                    jsonPath("$.refresh_token") { doesNotExist() }
                }.andReturn()
                .response.contentAsString

        val jwt = serviceDecoder(audience = "catalog").decode(accessToken(body))
        assertEquals("${issuerBaseUri.value}/realms/internal", jwt.issuer.toString())
        assertEquals(listOf("auth", "catalog"), jwt.audience)
        assertEquals(client.roles, jwt.getClaimAsStringList("roles"))
        assertEquals("system", jwt.getClaimAsString("principalType"))
        assertEquals(client.principalId.toString(), jwt.getClaimAsString("principalId"))
        assertEquals("system:${client.principalId}", jwt.subject)
        assertFalse(jwt.hasClaim("sid"))
        assertEquals(AuthPolicy.ACCESS_TOKEN_TTL, Duration.between(jwt.issuedAt, jwt.expiresAt))
    }

    @Test
    fun `role이 없는 client도 aud와 roles가 빈 토큰을 받음`() {
        val client = registerClient(roleAudiences = emptyList())

        val body =
            requestToken(basic(client.clientId, client.secret))
                .andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        val jwt = decodeWithoutAudienceCheck(accessToken(body))
        assertEquals(emptyList(), jwt.audience)
        assertEquals(emptyList(), jwt.getClaimAsStringList("roles"))
    }

    @Test
    fun `Basic 헤더의 client_id와 secret은 URL 디코드해서 인증`() {
        val secret = "a+b/c:d%e f"
        val client = registerClient(roleAudiences = emptyList(), secret = secret)
        val encoded = "${urlEncode(client.clientId)}:${urlEncode(secret)}"

        requestToken("Basic " + Base64.getEncoder().encodeToString(encoded.toByteArray())).andExpect { status { isOk() } }
    }

    @Test
    fun `CLI-03 secret이 다르면 invalid_client`() {
        val client = registerClient(roleAudiences = emptyList())

        requestToken(basic(client.clientId, "wrong-secret")).andExpectInvalidClient()
    }

    @Test
    fun `등록되지 않은 client면 invalid_client`() {
        requestToken(basic("svc-unknown-${suffix()}", OpaqueSecret.generate().value)).andExpectInvalidClient()
    }

    @Test
    fun `정지된 client는 secret이 맞아도 invalid_client`() {
        val client = registerClient(roleAudiences = emptyList())
        inTransaction { PrincipalTable.update({ PrincipalTable.id eq client.principalId }) { it[status] = "SUSPENDED" } }

        requestToken(basic(client.clientId, client.secret)).andExpectInvalidClient()
    }

    @Test
    fun `ACC-04 비활성화된 client는 원래 client_id로도 바뀐 client_id로도 invalid_client`() {
        val client = registerClient(roleAudiences = emptyList())
        val deletedClientId = "deleted-${client.principalId}"
        inTransaction {
            PrincipalTable.update({ PrincipalTable.id eq client.principalId }) { it[status] = "DEACTIVATED" }
            SystemClientTable.update({ SystemClientTable.principalId eq client.principalId }) {
                it[clientId] = deletedClientId
                it[clientSecretHash] = null
            }
        }

        requestToken(basic(client.clientId, client.secret)).andExpectInvalidClient()
        requestToken(basic(deletedClientId, client.secret)).andExpectInvalidClient()
    }

    @Test
    fun `Authorization 헤더가 없으면 invalid_client`() {
        requestToken(authorization = null).andExpectInvalidClient()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Basic",
            "Basic !!!",
            "Bearer c3ZjLXN0b3JlOnNlY3JldA==",
            // base64("svc-store") — ':' 없음
            "Basic c3ZjLXN0b3Jl",
            // base64(":secret") — client_id 없음
            "Basic OnNlY3JldA==",
            // base64("svc-store:") — secret 없음
            "Basic c3ZjLXN0b3JlOg==",
            // base64("svc-store:%zz") — 잘못된 URL 인코딩
            "Basic c3ZjLXN0b3JlOiV6eg==",
        ],
    )
    fun `Basic 헤더 형식이 틀리면 invalid_client`(authorization: String) {
        requestToken(authorization).andExpectInvalidClient()
    }

    @Test
    fun `grant_type이 없으면 invalid_request`() {
        requestToken(basic("svc-store", "secret"), body = "").andExpectOAuthError(400, "invalid_request")
    }

    @Test
    fun `grant_type을 두 번 보내면 invalid_request`() {
        requestToken(basic("svc-store", "secret"), body = "grant_type=client_credentials&grant_type=client_credentials")
            .andExpectOAuthError(400, "invalid_request")
    }

    @Test
    fun `form이 아닌 본문은 grant_type이 없는 것으로 보고 invalid_request`() {
        mockMvc
            .post(SystemTokenController.PATH) {
                header(HttpHeaders.AUTHORIZATION, basic("svc-store", "secret"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"grant_type":"client_credentials"}"""
            }.andExpectOAuthError(400, "invalid_request")
    }

    @ParameterizedTest
    @ValueSource(strings = ["password", "authorization_code", "refresh_token"])
    fun `client_credentials가 아니면 unsupported_grant_type`(grantType: String) {
        requestToken(basic("svc-store", "secret"), body = "grant_type=$grantType").andExpectOAuthError(400, "unsupported_grant_type")
    }

    @Test
    fun `본문의 client_secret과 Basic 헤더를 함께 보내면 invalid_request`() {
        val client = registerClient(roleAudiences = emptyList())

        requestToken(
            basic(client.clientId, client.secret),
            body = "grant_type=client_credentials&client_id=${client.clientId}&client_secret=${client.secret}",
        ).andExpectOAuthError(400, "invalid_request")
    }

    @Test
    fun `본문의 client_secret만으로는 인증하지 않고 invalid_client`() {
        val client = registerClient(roleAudiences = emptyList())

        requestToken(
            authorization = null,
            body = "grant_type=client_credentials&client_id=${client.clientId}&client_secret=${client.secret}",
        ).andExpectInvalidClient()
    }

    @Test
    fun `발급하면 client_id, 요청 IP, 결과를 로그 한 줄로 남기고 secret, 토큰, Authorization 헤더는 남기지 않음`(output: CapturedOutput) {
        val client = registerClient(roleAudiences = listOf("catalog"))
        val authorization = basic(client.clientId, client.secret)

        val body =
            requestToken(authorization)
                .andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        val line = output.issuanceLines(client.clientId).single()
        assertTrue("ip=127.0.0.1" in line, line)
        assertTrue("result=issued" in line, line)
        assertFalse(client.secret in output.all)
        assertFalse(accessToken(body) in output.all)
        assertFalse(authorization.removePrefix("Basic ") in output.all)
    }

    @Test
    fun `CLI-03 인증에 실패하면 결과를 invalid_client로 남기고 제시한 secret은 남기지 않음`(output: CapturedOutput) {
        val client = registerClient(roleAudiences = emptyList())
        val wrongSecret = "wrong-secret-${suffix()}"

        requestToken(basic(client.clientId, wrongSecret)).andExpectInvalidClient()

        val line = output.issuanceLines(client.clientId).single()
        assertTrue("result=invalid_client" in line, line)
        assertFalse(wrongSecret in output.all)
    }

    @Test
    fun `CLI-01 형식이 아닌 client_id는 로그에 남기지 않음`(output: CapturedOutput) {
        val clientId = "Not_A_Client_${suffix()}"

        requestToken(basic(clientId, "secret")).andExpectInvalidClient()

        assertFalse(clientId in output.all)
        assertTrue(output.all.lines().any { "client_id=invalid format," in it && "result=invalid_client" in it })
    }

    @Test
    fun `요청 형식이 틀려도 결과를 로그로 남김`(output: CapturedOutput) {
        val unsupported = "svc-log-${suffix()}"
        val missingGrantType = "svc-log-${suffix()}"

        requestToken(basic(unsupported, "secret"), body = "grant_type=password").andExpectOAuthError(400, "unsupported_grant_type")
        requestToken(basic(missingGrantType, "secret"), body = "").andExpectOAuthError(400, "invalid_request")

        assertTrue("result=unsupported_grant_type" in output.issuanceLines(unsupported).single())
        assertTrue("result=invalid_request" in output.issuanceLines(missingGrantType).single())
    }

    /** [clientId]의 발급 로그 줄. client_id 뒤의 구분자까지 맞춰 다른 client_id의 앞부분과 겹치지 않게 합니다. */
    private fun CapturedOutput.issuanceLines(clientId: String): List<String> = all.lines().filter { "client_id=$clientId," in it }

    private fun requestToken(
        authorization: String?,
        body: String = "grant_type=client_credentials",
    ): ResultActionsDsl =
        mockMvc.post(SystemTokenController.PATH) {
            if (authorization != null) header(HttpHeaders.AUTHORIZATION, authorization)
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = body
        }

    private fun ResultActionsDsl.andExpectInvalidClient() {
        andExpectOAuthError(401, "invalid_client")
        andExpect { header { string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"internal\"") } }
    }

    private fun ResultActionsDsl.andExpectOAuthError(
        expectedStatus: Int,
        error: String,
    ) {
        andExpect {
            status { isEqualTo(expectedStatus) }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
            header { string(HttpHeaders.PRAGMA, "no-cache") }
            jsonPath("$.error") { value(error) }
            jsonPath("$.error_description") { exists() }
            jsonPath("$.code") { doesNotExist() }
            jsonPath("$.access_token") { doesNotExist() }
        }
    }

    private fun accessToken(body: String): String = jsonMapper.readTree(body).get("access_token").asString()

    /** [audience] 서비스가 쓰는 스타터 디코더. 공개키는 JWKS API 응답입니다. */
    private fun serviceDecoder(audience: String): JwtDecoder = DozyJwtDecoders.create(serviceProperties(audience), publishedKeys(), clock)

    /** `aud`가 없는 토큰은 어느 서비스도 받지 않으므로, claim을 읽을 때만 `aud` 검사를 뺀 디코더를 씁니다. */
    private fun decodeWithoutAudienceCheck(token: String): Jwt =
        DozyJwtDecoders.createWithoutAudienceCheck(serviceProperties("catalog"), publishedKeys(), clock).decode(token)

    private fun serviceProperties(audience: String) =
        DozyAuthProperties(audience = audience, acceptedRealms = setOf(Realm.INTERNAL), issuerBaseUri = issuerBaseUri.value)

    private fun publishedKeys(): JWKSource<SecurityContext> =
        ImmutableJWKSet(
            JWKSet.parse(
                mockMvc
                    .get(JwksController.PATH)
                    .andReturn()
                    .response.contentAsString,
            ),
        )

    /** SQL 운영 절차와 같은 순서로 system principal, client, role 부여를 만듭니다. */
    private fun registerClient(
        roleAudiences: List<String>,
        secret: String = OpaqueSecret.generate().value,
    ): RegisteredClient {
        val clientId = "svc-it-${suffix()}"
        val now = clock.instant()
        return inTransaction {
            val principalId =
                PrincipalTable
                    .insertReturning(listOf(PrincipalTable.id)) {
                        it[type] = "SYSTEM"
                        it[status] = "ACTIVE"
                    }.single()[PrincipalTable.id]
            SystemClientTable.insert {
                it[SystemClientTable.principalId] = principalId
                it[SystemClientTable.clientId] = clientId
                it[clientSecretHash] = SecretHash.of(secret).hex
                it[name] = "통합 테스트"
                it[secretRotatedAt] = now
            }
            val granted =
                roleAudiences.map { audienceCode ->
                    val audience = checkNotNull(roles.findAudienceByCode(audienceCode))
                    val role = roles.createRole(audience, "it_${suffix()}", "통합 테스트", null, null, now)
                    PrincipalRoleTable.insert {
                        it[PrincipalRoleTable.principalId] = principalId
                        it[roleId] = role.id
                        it[grantedAt] = now
                    }
                    role.code
                }
            RegisteredClient(principalId, clientId, secret, granted.sortedBy(RoleCode::value).map(RoleCode::value))
        }
    }

    private fun <T> inTransaction(block: () -> T): T = checkNotNull(TransactionTemplate(transactionManager).execute { block() })

    private fun basic(
        clientId: String,
        secret: String,
    ): String = "Basic " + Base64.getEncoder().encodeToString("${urlEncode(clientId)}:${urlEncode(secret)}".toByteArray())

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun suffix(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(SUFFIX_LENGTH)

    private class RegisteredClient(
        val principalId: UUID,
        val clientId: String,
        val secret: String,
        val roles: List<String>,
    )

    private companion object {
        const val SUFFIX_LENGTH = 12
    }
}
