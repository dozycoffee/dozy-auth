package com.dozycoffee.auth.server.adapter.outbound.jwt

import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.dozycoffee.auth.server.support.TestSigningKeys
import com.dozycoffee.auth.server.support.TestSigningKeys.CURRENT_KID
import com.dozycoffee.auth.server.support.TestSigningKeys.NEXT_KID
import com.dozycoffee.auth.server.support.TokenFixtures
import com.dozycoffee.auth.server.support.TokenFixtures.EMPLOYEE
import com.dozycoffee.auth.server.support.TokenFixtures.FIXED_CLOCK
import com.dozycoffee.auth.server.support.TokenFixtures.ISSUER_BASE
import com.dozycoffee.auth.server.support.TokenFixtures.NOW
import com.dozycoffee.auth.server.support.TokenFixtures.PARTNER
import com.dozycoffee.auth.server.support.TokenFixtures.SESSION_ID
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import com.dozycoffee.auth.server.support.TokenFixtures.TOKEN_ID
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * token.md §2, §3, §7.
 *
 * header·claim의 이름과 형식은 서비스와의 계약이라 명세의 문자열을 그대로 기대값으로 씁니다. 입력에서 온 값(issuer 기준 주소, principal id)만 fixture를 씁니다.
 * `ClaimNames` 같은 구현 상수를 쓰면 상수가 잘못 바뀌어도 테스트가 통과하기 때문입니다.
 */
class JwtNimbusAdapterTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `서명한 토큰을 JWKS의 공개키로 검증`() {
        val adapter = adapter(activeKid = CURRENT_KID)

        val jwt = SignedJWT.parse(adapter.sign(TokenFixtures.accessTokenClaims()))
        val publicKey = JWKSet.parse(adapter.load()).getKeyByKeyId(jwt.header.keyID).toRSAKey()

        assertTrue(jwt.verify(RSASSAVerifier(publicKey)))
    }

    @Test
    fun `header는 RS256, at+jwt, 활성 kid`() {
        val header = SignedJWT.parse(adapter(activeKid = NEXT_KID).sign(TokenFixtures.accessTokenClaims())).header

        assertEquals("RS256", header.algorithm.name)
        assertEquals("at+jwt", header.type.type)
        assertEquals(NEXT_KID, header.keyID)
    }

    @Test
    fun `토큰에 주체, audience, role, 발급·만료 시각, 토큰·세션 id를 담음`() {
        val claims = TokenFixtures.accessTokenClaims()
        val body = SignedJWT.parse(adapter(activeKid = CURRENT_KID).sign(claims)).jwtClaimsSet

        assertEquals("${ISSUER_BASE.value}/realms/internal", body.issuer)
        assertEquals("employee:${EMPLOYEE.id}", body.subject)
        assertEquals(listOf("wms", "catalog"), body.audience)
        assertEquals("employee", body.getStringClaim("principalType"))
        assertEquals(EMPLOYEE.id.toString(), body.getStringClaim("principalId"))
        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor"), body.getStringListClaim("roles"))
        assertEquals(Date.from(claims.issuedAt), body.issueTime)
        assertEquals(Date.from(claims.expiresAt), body.expirationTime)
        assertEquals(TOKEN_ID, body.jwtid)
        assertEquals(SESSION_ID, body.getStringClaim("sid"))
    }

    @Test
    fun `system token에는 sid가 없고 roles가 비어도 배열로 들어감`() {
        val claims = TokenFixtures.accessTokenClaims(principal = SYSTEM, audience = emptyList(), roles = emptyList(), sessionId = null)

        val body = SignedJWT.parse(adapter(activeKid = CURRENT_KID).sign(claims)).jwtClaimsSet

        assertNull(body.getClaim("sid"))
        assertEquals(emptyList(), body.getStringListClaim("roles"))
    }

    /** 라이브러리가 claim을 읽으며 형식을 맞춰 주지 않도록, 서명된 본문의 JSON 원문을 검사합니다. */
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `aud는 audience 수와 관계없이 항상 배열`(count: Int) {
        val audience = listOf("wms", "catalog").take(count)
        val roles = listOf("wms:inbound_manager", "catalog:menu_editor").take(count)

        val body = rawBody(TokenFixtures.accessTokenClaims(audience = audience, roles = roles))

        assertEquals(audience, body["aud"])
    }

    @Test
    fun `파트너 토큰의 aud도 배열`() {
        val body = rawBody(TokenFixtures.accessTokenClaims(principal = PARTNER, audience = listOf("store"), roles = emptyList()))

        assertEquals(listOf("store"), body["aud"])
    }

    @Test
    fun `iat와 exp는 초 단위 숫자`() {
        val claims = TokenFixtures.accessTokenClaims()

        val body = rawBody(claims)

        assertEquals(NOW.epochSecond, body["iat"])
        assertEquals(claims.expiresAt.epochSecond, body["exp"])
    }

    @Test
    fun `JWKS는 게시 중인 모든 키의 공개키만 담음`() {
        val jwks = adapter(activeKid = CURRENT_KID).load()

        val keys = JWKSet.parse(jwks).keys
        assertEquals(listOf(CURRENT_KID, NEXT_KID), keys.map { it.keyID })
        assertTrue(keys.none { it.isPrivate })
        assertTrue(keys.all { it.algorithm.name == "RS256" && it.keyUse.identifier() == "sig" })
    }

    private fun rawBody(claims: AccessTokenClaims): Map<String, Any?> =
        JSONObjectUtils.parse(SignedJWT.parse(adapter(activeKid = CURRENT_KID).sign(claims)).payload.toString())

    private fun adapter(activeKid: String): JwtNimbusAdapter {
        TestSigningKeys.writeCurrentAndNext(dir)
        return JwtNimbusAdapter(SigningKeyLoader(FIXED_CLOCK).load(SigningKeyProperties(dir, activeKid = activeKid)))
    }
}
