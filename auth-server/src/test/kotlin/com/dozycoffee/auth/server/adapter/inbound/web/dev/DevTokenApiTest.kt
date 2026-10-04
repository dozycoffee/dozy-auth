package com.dozycoffee.auth.server.adapter.inbound.web.dev

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.inbound.web.internal.JwksController
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import com.dozycoffee.auth.server.support.TokenFixtures.EMPLOYEE
import com.dozycoffee.auth.server.support.TokenFixtures.PARTNER
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import com.dozycoffee.auth.starter.DozyAuthProperties
import com.dozycoffee.auth.starter.DozyJwtDecoders
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 개발용 토큰 발급 API (api/dev.md). 발급한 토큰은 서비스 입장에서 JWKS API의 공개키와 스타터 검증기로 검증합니다 (token.md §6).
 *
 * 컨트롤러가 `local`·`dev` 프로필에서만 등록되므로 `local` 프로필을 함께 켭니다. 설정 값은 뒤에 둔 `test` 프로필이 우선하고
 * (DB, 서명 키 폴더, 메일), Docker Compose 지원은 테스트 클래스패스에 없어 동작하지 않습니다. `dev` 프로필은 환경 변수가 필요해 쓰지 않습니다.
 * 다른 프로필에서 막히는 것은 [DevTokenProfileTest]에서 확인합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("local", "test")
class DevTokenApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var issuerBaseUri: IssuerBaseUri

    @Autowired
    lateinit var clock: Clock

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `직원 토큰을 발급하고 role의 audience 서비스가 검증할 수 있음`() {
        val response =
            issue(
                """{"realm":"internal","principalType":"employee","principalId":"${EMPLOYEE.id}","roles":["wms:inbound_manager","catalog:menu_editor"]}""",
            )

        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(null, response.getHeader("Set-Cookie"))
        val body = json(response)
        assertEquals("Bearer", body["tokenType"])
        assertEquals(AuthPolicy.ACCESS_TOKEN_TTL.seconds, (body["expiresIn"] as Number).toLong())

        val jwt = verify(body["accessToken"] as String, audience = "catalog", realm = Realm.INTERNAL)
        assertEquals("${issuerBaseUri.value}/realms/internal", jwt.issuer.toString())
        assertEquals("employee:${EMPLOYEE.id}", jwt.subject)
        assertEquals(listOf("wms", "catalog"), jwt.audience)
        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor"), jwt.getClaimAsStringList("roles"))
        assertFalse(jwt.hasClaim("sid"))
    }

    @Test
    fun `system 토큰은 세션 id 없이 발급되고 검증을 통과`() {
        val response =
            issue("""{"realm":"internal","principalType":"system","principalId":"${SYSTEM.id}","roles":["wms:stock_reader"]}""")

        assertEquals(200, response.status)
        val jwt = verify(json(response)["accessToken"] as String, audience = "wms", realm = Realm.INTERNAL)
        assertEquals("system:${SYSTEM.id}", jwt.subject)
        assertFalse(jwt.hasClaim("sid"))
    }

    @Test
    fun `파트너 토큰은 roles 없이 발급되고 store가 검증할 수 있음`() {
        val response = issue("""{"realm":"partner","principalType":"partner","principalId":"${PARTNER.id}"}""")

        assertEquals(200, response.status)
        val jwt = verify(json(response)["accessToken"] as String, audience = "store", realm = Realm.PARTNER)
        assertEquals("${issuerBaseUri.value}/realms/partner", jwt.issuer.toString())
        assertEquals(listOf("store"), jwt.audience)
        assertEquals(emptyList(), jwt.getClaimAsStringList("roles"))
    }

    @Test
    fun `DOM-01 realm과 principal type 조합이 틀리면 400 VALIDATION_FAILED`() {
        val response = issue("""{"realm":"partner","principalType":"employee","principalId":"${EMPLOYEE.id}"}""")

        assertValidationFailed(response)
    }

    @Test
    fun `DOM-04 파트너 토큰에 roles가 있으면 400 VALIDATION_FAILED`() {
        val response =
            issue("""{"realm":"partner","principalType":"partner","principalId":"${PARTNER.id}","roles":["store:owner"]}""")

        assertValidationFailed(response)
    }

    @Test
    fun `DOM-03 role 형식이 틀리거나 null이면 400 VALIDATION_FAILED`() {
        for (roles in listOf("""["wms-inbound"]""", """[null]""")) {
            val response =
                issue("""{"realm":"internal","principalType":"employee","principalId":"${EMPLOYEE.id}","roles":$roles}""")

            assertValidationFailed(response)
        }
    }

    @Test
    fun `필수 필드가 없으면 400 VALIDATION_FAILED`() {
        val response = issue("""{"realm":"internal","principalType":"employee"}""")

        assertValidationFailed(response)
    }

    @Test
    fun `IP 단위 요청 제한을 받지 않음`() {
        val body = """{"realm":"internal","principalType":"employee","principalId":"${EMPLOYEE.id}"}"""

        val statuses = List(AuthPolicy.RATE_LIMIT_IP.capacity + 1) { issue(body).status }

        assertTrue(statuses.all { it == 200 })
    }

    private fun issue(body: String): MockHttpServletResponse =
        mockMvc
            .post(DevTokenController.TOKENS_PATH) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun assertValidationFailed(response: MockHttpServletResponse) {
        assertEquals(400, response.status)
        assertEquals("application/problem+json", response.contentType)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    /** token.md §6의 2~9를 [realm]을 받는 [audience] 서비스 입장에서 검증합니다. 실패하면 예외가 납니다. */
    private fun verify(
        token: String,
        audience: String,
        realm: Realm,
    ): Jwt {
        val jwks =
            mockMvc
                .get(JwksController.PATH)
                .andReturn()
                .response.contentAsString
        val properties = DozyAuthProperties(audience = audience, acceptedRealms = setOf(realm), issuerBaseUri = issuerBaseUri.value)
        return DozyJwtDecoders.create(properties, ImmutableJWKSet(JWKSet.parse(jwks)), clock).decode(token)
    }
}
