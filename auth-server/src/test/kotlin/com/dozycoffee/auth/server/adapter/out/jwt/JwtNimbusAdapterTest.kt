package com.dozycoffee.auth.server.adapter.out.jwt

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtNimbusAdapterTest {
    @TempDir
    lateinit var dir: Path

    private val principal = PrincipalKey(PrincipalType.EMPLOYEE, UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f"))
    private val issuedAt = Instant.parse("2026-09-25T00:00:00Z")

    private val claims =
        AccessTokenClaims(
            issuer = "https://auth.dozycoffee.com/realms/internal",
            principal = principal,
            audience = listOf("wms", "catalog"),
            roles = listOf("wms:inbound_manager", "catalog:menu_editor"),
            issuedAt = issuedAt,
            expiresAt = issuedAt.plusSeconds(600),
            tokenId = "5f2b9c1e-8a4d-4c1e-9d3f-2b7a6e0c1d4f",
            sessionId = "8c1d4f5f-2b9c-4e8a-a4d1-c9d3f2b7a6e0",
        )

    @Test
    fun `서명한 토큰을 JWKS의 공개키로 검증`() {
        val adapter = adapter(active = "dozy-2026-09")

        val jwt = SignedJWT.parse(adapter.sign(claims))
        val publicKey = JWKSet.parse(adapter.load()).getKeyByKeyId(jwt.header.keyID).toRSAKey()

        assertTrue(jwt.verify(RSASSAVerifier(publicKey)))
    }

    @Test
    fun `header는 RS256, at+jwt, 활성 kid`() {
        val header = SignedJWT.parse(adapter(active = "dozy-2027-09").sign(claims)).header

        assertEquals("RS256", header.algorithm.name)
        assertEquals("at+jwt", header.type.type)
        assertEquals("dozy-2027-09", header.keyID)
    }

    @Test
    fun `claim은 token 명세 3장 형식`() {
        val body = SignedJWT.parse(adapter(active = "dozy-2026-09").sign(claims)).jwtClaimsSet

        assertEquals(claims.issuer, body.issuer)
        assertEquals("employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", body.subject)
        assertEquals(listOf("wms", "catalog"), body.audience)
        assertEquals("employee", body.getStringClaim("principalType"))
        assertEquals("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", body.getStringClaim("principalId"))
        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor"), body.getStringListClaim("roles"))
        assertEquals(Date.from(issuedAt), body.issueTime)
        assertEquals(Date.from(issuedAt.plusSeconds(600)), body.expirationTime)
        assertEquals(claims.tokenId, body.jwtid)
        assertEquals(claims.sessionId, body.getStringClaim("sid"))
    }

    @Test
    fun `system token에는 sid가 없고 roles가 비어도 배열로 들어감`() {
        val systemClaims =
            claims.copy(
                principal = PrincipalKey(PrincipalType.SYSTEM, UUID.randomUUID()),
                roles = emptyList(),
                sessionId = null,
            )

        val body = SignedJWT.parse(adapter(active = "dozy-2026-09").sign(systemClaims)).jwtClaimsSet

        assertNull(body.getClaim("sid"))
        assertEquals(emptyList(), body.getStringListClaim("roles"))
    }

    @Test
    fun `JWKS는 게시 중인 모든 키의 공개키만 담음`() {
        val jwks = adapter(active = "dozy-2026-09").load()

        val keys = JWKSet.parse(jwks).keys
        assertEquals(listOf("dozy-2026-09", "dozy-2027-09"), keys.map { it.keyID })
        assertTrue(keys.none { it.isPrivate })
        assertFalse(jwks.toString().contains("\"d\""))
        assertTrue(keys.all { it.algorithm.name == "RS256" && it.keyUse.identifier() == "sig" })
    }

    private fun adapter(active: String): JwtNimbusAdapter {
        dir.resolve("dozy-2026-09.pem").writeText(KEY_A)
        dir.resolve("dozy-2027-09.pem").writeText(KEY_B)
        val clock = Clock.fixed(issuedAt, ZoneOffset.UTC)
        return JwtNimbusAdapter(SigningKeyLoader(clock).load(SigningKeyProperties(dir, activeKid = active)))
    }

    companion object {
        private val KEY_A = SigningKeyPem.generate(SigningKeyLoader.MIN_KEY_SIZE, SecureRandom())
        private val KEY_B = SigningKeyPem.generate(SigningKeyLoader.MIN_KEY_SIZE, SecureRandom())
    }
}
