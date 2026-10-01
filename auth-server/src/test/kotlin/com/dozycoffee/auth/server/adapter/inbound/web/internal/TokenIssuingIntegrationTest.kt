package com.dozycoffee.auth.server.adapter.inbound.web.internal

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
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
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Clock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 발급한 토큰을 JWKS API로 받은 공개키로 검증합니다 (token.md §6의 2~9).
 * 스타터의 검증 체인이 생기기 전까지 Nimbus로 직접 검증합니다.
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

        val claims = verify(token, audience = "wms", issuerPath = "/realms/internal", realm = Realm.INTERNAL)

        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor"), claims.getStringListClaim("roles"))
    }

    @Test
    fun `파트너 토큰은 store audience로 서명과 claim 검증을 모두 통과`() {
        val token = issue(PARTNER, Realm.PARTNER, emptyList(), SESSION_ID)

        verify(token, audience = "store", issuerPath = "/realms/partner", realm = Realm.PARTNER)
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

    @Test
    fun `아직 열지 않은 경로는 인증 없이는 401`() {
        mockMvc.get("/realms/internal/me").andExpect { status { isUnauthorized() } }
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

    /** token.md §6의 2~9를 서비스 입장에서 검증합니다. 실패하면 예외가 납니다. */
    private fun verify(
        token: String,
        audience: String,
        issuerPath: String,
        realm: Realm,
    ): JWTClaimsSet {
        val jwks =
            mockMvc
                .get(JwksController.PATH)
                .andReturn()
                .response.contentAsString

        val processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                // 3. typ
                jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(JOSEObjectType("at+jwt"))
                // 2, 4, 5. alg는 RS256만, kid로 JWKS에서 키를 찾아 서명 검증
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, ImmutableJWKSet(JWKSet.parse(jwks)))
                // 6, 7, 8. exp·iat, iss, aud
                jwtClaimsSetVerifier =
                    DefaultJWTClaimsVerifier(
                        audience,
                        JWTClaimsSet.Builder().issuer("${issuerBaseUri.value}$issuerPath").build(),
                        setOf("sub", "iat", "exp", "jti", "principalType", "principalId", "roles"),
                    )
            }
        val claims = processor.process(token, null)

        // 9. principalType·principalId·sub 일치, realm과 type 조합 (DOM-01)
        val type = PrincipalType.fromClaimValue(claims.getStringClaim("principalType"))
        val key = PrincipalKey(type, PrincipalKey.parseId(claims.getStringClaim("principalId")))
        assertEquals(key.sub, claims.subject)
        assertTrue(realm.allows(type))
        return claims
    }
}
