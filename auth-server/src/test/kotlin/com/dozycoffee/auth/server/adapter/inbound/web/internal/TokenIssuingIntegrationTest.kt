package com.dozycoffee.auth.server.adapter.inbound.web.internal

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.token.AccessTokenFactory
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import com.dozycoffee.auth.server.support.TokenFixtures.EMPLOYEE
import com.dozycoffee.auth.server.support.TokenFixtures.PARTNER
import com.dozycoffee.auth.server.support.TokenFixtures.SESSION_ID
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
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 발급한 토큰을 서비스 입장에서 JWKS API로 받은 공개키와 스타터 검증기로 검증합니다 (token.md §6의 2~9).
 * 검증 규칙을 테스트에서 따로 흉내 내지 않고 서비스가 쓰는 것과 같은 스타터 코드를 씁니다.
 *
 * `at+jwt`, claim 이름, 캐시 헤더 같은 명세 값은 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class TokenIssuingIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var signToken: SignTokenPort

    @Autowired
    lateinit var issuerBaseUri: IssuerBaseUri

    @Autowired
    lateinit var clock: Clock

    @Test
    fun `직원 토큰은 JWKS 공개키로 서명과 claim 검증을 모두 통과`() {
        val token = issue(EMPLOYEE, Realm.INTERNAL, listOf("wms:inbound_manager", "catalog:menu_editor"), SESSION_ID)

        val jwt = verify(token, audience = "wms", realm = Realm.INTERNAL)

        assertEquals("${issuerBaseUri.value}/realms/internal", jwt.issuer.toString())
        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor"), jwt.getClaimAsStringList("roles"))
    }

    @Test
    fun `파트너 토큰은 store audience로 서명과 claim 검증을 모두 통과`() {
        val token = issue(PARTNER, Realm.PARTNER, emptyList(), SESSION_ID)

        val jwt = verify(token, audience = "store", realm = Realm.PARTNER)

        assertEquals("${issuerBaseUri.value}/realms/partner", jwt.issuer.toString())
    }

    @Test
    fun `JWKS는 인증 없이 공개키만 캐시 헤더와 함께 응답`() {
        mockMvc.get(JwksController.PATH).andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            header { string("Cache-Control", "max-age=${AuthPolicy.JWKS_CACHE_MAX_AGE.seconds}, public") }
            jsonPath("$.keys[0].kid") { exists() }
            jsonPath("$.keys[0].alg") { value("RS256") }
            jsonPath("$.keys[0].use") { value("sig") }
            for (field in listOf("d", "p", "q", "dp", "dq", "qi")) jsonPath("$.keys[0].$field") { doesNotExist() }
        }
    }

    private fun issue(
        principal: PrincipalKey,
        realm: Realm,
        roles: List<String>,
        sessionId: String?,
    ): String {
        val claims =
            AccessTokenFactory.create(
                principal = principal,
                realm = realm,
                roles = roles.map(RoleCode::parse),
                sessionId = sessionId,
                issuerBaseUri = issuerBaseUri,
                issuedAt = clock.instant(),
                tokenId = UUID.randomUUID().toString(),
            )
        return signToken.sign(claims)
    }

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
