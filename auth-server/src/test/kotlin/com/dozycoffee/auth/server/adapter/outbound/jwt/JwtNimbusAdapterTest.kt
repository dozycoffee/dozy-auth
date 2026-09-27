package com.dozycoffee.auth.server.adapter.outbound.jwt

import com.dozycoffee.auth.server.support.TestSigningKeys
import com.dozycoffee.auth.server.support.TestSigningKeys.CURRENT_KID
import com.dozycoffee.auth.server.support.TestSigningKeys.NEXT_KID
import com.dozycoffee.auth.server.support.TokenFixtures
import com.dozycoffee.auth.server.support.TokenFixtures.FIXED_CLOCK
import com.dozycoffee.auth.server.support.TokenFixtures.NOW
import com.dozycoffee.auth.server.support.TokenFixtures.SESSION_ID
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import com.dozycoffee.auth.server.support.TokenFixtures.TOKEN_ID
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * token.md §2, §3, §7.
 *
 * header·claim의 이름과 형식은 서비스와의 계약이라 명세의 문자열을 그대로 기대값으로 씁니다.
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
    fun `claim은 token 명세 3장의 이름과 형식`() {
        val body = SignedJWT.parse(adapter(activeKid = CURRENT_KID).sign(TokenFixtures.accessTokenClaims())).jwtClaimsSet

        assertEquals("https://auth.dozycoffee.com/realms/internal", body.issuer)
        assertEquals("employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", body.subject)
        assertEquals(listOf("wms", "catalog"), body.audience)
        assertEquals("employee", body.getStringClaim("principalType"))
        assertEquals("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", body.getStringClaim("principalId"))
        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor"), body.getStringListClaim("roles"))
        assertEquals(Date.from(NOW), body.issueTime)
        assertEquals(Date.from(NOW.plusSeconds(600)), body.expirationTime)
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

    @Test
    fun `JWKS는 게시 중인 모든 키의 공개키만 담음`() {
        val jwks = adapter(activeKid = CURRENT_KID).load()

        val keys = JWKSet.parse(jwks).keys
        assertEquals(listOf(CURRENT_KID, NEXT_KID), keys.map { it.keyID })
        assertTrue(keys.none { it.isPrivate })
        assertTrue(keys.all { it.algorithm.name == "RS256" && it.keyUse.identifier() == "sig" })
    }

    private fun adapter(activeKid: String): JwtNimbusAdapter {
        TestSigningKeys.writeCurrentAndNext(dir)
        return JwtNimbusAdapter(SigningKeyLoader(FIXED_CLOCK).load(SigningKeyProperties(dir, activeKid = activeKid)))
    }
}
