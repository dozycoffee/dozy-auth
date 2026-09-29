package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.starter.support.JwksServer
import com.dozycoffee.auth.starter.support.TestKeys
import com.dozycoffee.auth.starter.support.TestTokens.EMPLOYEE_ID
import com.dozycoffee.auth.starter.support.TestTokens.FIXED_CLOCK
import com.dozycoffee.auth.starter.support.TestTokens.ISSUER_BASE
import com.dozycoffee.auth.starter.support.TestTokens.NOW
import com.dozycoffee.auth.starter.support.TestTokens.employeeClaims
import com.dozycoffee.auth.starter.support.TestTokens.partnerClaims
import com.dozycoffee.auth.starter.support.TestTokens.sign
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * token.md §6의 2~9. 검증에 실패하면 [JwtException]이고, 스타터는 이를 401로 응답합니다.
 *
 * Spring MVC용과 WebFlux용 디코더가 같은 규칙을 지키는지 두 하위 클래스에서 같은 테스트를 돌립니다.
 */
abstract class DozyJwtDecodersTest {
    /** 디코더를 만들어 토큰을 해독하는 함수를 돌려줍니다. */
    protected abstract fun decoderFor(
        properties: DozyAuthProperties,
        refetchMinInterval: Duration,
    ): (String) -> Jwt

    private val jwks = JwksServer()

    @AfterEach
    fun stop() = jwks.close()

    @Test
    fun `정상 직원 토큰을 받음`() {
        val jwt = decoder().decode(sign(employeeClaims()))

        assertEquals("employee:$EMPLOYEE_ID", jwt.subject)
    }

    @Test
    fun `여러 realm을 받는 서비스는 파트너 토큰도 받음`() {
        decoder(audience = "store", realms = setOf(Realm.INTERNAL, Realm.PARTNER)).decode(sign(partnerClaims()))
    }

    // 2. alg

    @Test
    fun `RS256이 아닌 HS256 토큰은 거부`() {
        val secret = ByteArray(32) { 1 }
        val token = JWSObject(JWSHeader(JWSAlgorithm.HS256), Payload(employeeClaims())).apply { sign(MACSigner(secret)) }.serialize()

        assertRejected(token)
    }

    @Test
    fun `서명 없는 alg none 토큰은 거부`() {
        assertRejected(PlainJWT(JWTClaimsSet.parse(employeeClaims())).serialize())
    }

    // 3. typ

    @Test
    fun `typ이 at+jwt가 아니면 거부`() {
        assertRejected(sign(employeeClaims(), type = "JWT"))
    }

    @Test
    fun `typ이 없으면 거부`() {
        assertRejected(sign(employeeClaims(), type = null))
    }

    // 4, 5. kid, 서명

    @Test
    fun `JWKS에 없는 키로 서명한 토큰은 거부`() {
        assertRejected(sign(employeeClaims(), key = TestKeys.UNKNOWN))
    }

    @Test
    fun `본문을 바꾼 토큰은 서명 불일치로 거부`() {
        val (header, _, signature) = sign(employeeClaims()).split(".")
        val forgedBody = sign(employeeClaims("roles" to listOf("wms:admin"))).split(".")[1]

        assertRejected("$header.$forgedBody.$signature")
    }

    @Test
    fun `모르는 kid가 오면 JWKS를 다시 받아 새 키로 서명한 토큰을 받음`() {
        val decoder = decoder(refetchMinInterval = Duration.ZERO)
        decoder.decode(sign(employeeClaims()))

        jwks.published = listOf(TestKeys.CURRENT, TestKeys.NEXT)

        decoder.decode(sign(employeeClaims(), key = TestKeys.NEXT))
        assertEquals(2, jwks.fetchCount.get())
    }

    /** 최소 간격 안에서는 처음 받은 뒤 한 번만 다시 받습니다 (Nimbus 재조회 제한: 간격당 두 번). */
    @Test
    fun `모르는 kid가 계속 와도 최소 간격 안에서는 JWKS를 한 번만 다시 받음`() {
        val decoder = decoder(refetchMinInterval = Duration.ofMinutes(1))
        decoder.decode(sign(employeeClaims()))
        val fetchesAfterFirstLoad = jwks.fetchCount.get()

        repeat(5) { assertFailsWith<JwtException> { decoder.decode(sign(employeeClaims(), key = TestKeys.UNKNOWN)) } }

        assertEquals(fetchesAfterFirstLoad + 1, jwks.fetchCount.get())
    }

    // 6. exp, iat

    @Test
    fun `만료된 토큰은 거부`() {
        val expired = NOW.minus(CLOCK_SKEW).minusSeconds(1)
        val issuedAt = expired.minusSeconds(600)

        assertRejected(sign(employeeClaims("iat" to issuedAt.epochSecond, "exp" to expired.epochSecond)))
    }

    @Test
    fun `만료 직후라도 시계 오차 안이면 받음`() {
        val justExpired = NOW.minus(CLOCK_SKEW)
        val issuedAt = justExpired.minusSeconds(600)

        decoder().decode(sign(employeeClaims("iat" to issuedAt.epochSecond, "exp" to justExpired.epochSecond)))
    }

    @Test
    fun `exp가 없으면 거부`() {
        assertRejected(sign(employeeClaims("exp" to null)))
    }

    @Test
    fun `iat가 시계 오차보다 미래면 거부`() {
        val future = NOW.plus(CLOCK_SKEW).plusSeconds(1)

        assertRejected(sign(employeeClaims("iat" to future.epochSecond)))
    }

    @Test
    fun `iat가 없으면 거부`() {
        assertRejected(sign(employeeClaims("iat" to null)))
    }

    // 7. iss

    @Test
    fun `허용하지 않은 realm의 issuer면 거부`() {
        assertRejected(sign(partnerClaims("aud" to listOf("wms"))))
    }

    @Test
    fun `다른 Auth 주소의 issuer면 거부`() {
        assertRejected(sign(employeeClaims("iss" to "https://evil.example.com/realms/internal")))
    }

    // 8. aud

    @Test
    fun `aud에 이 서비스가 없으면 거부`() {
        assertRejected(sign(employeeClaims("aud" to listOf("catalog"))))
    }

    @Test
    fun `aud가 비어 있으면 거부`() {
        assertRejected(sign(employeeClaims("aud" to emptyList<String>())))
    }

    // 9. principalType, principalId, sub, roles

    @Test
    fun `DOM-01 realm이 받을 수 없는 principal type이면 거부`() {
        val partnerInInternalRealm = partnerClaims("iss" to "$ISSUER_BASE/realms/internal", "aud" to listOf("wms"))

        assertRejected(sign(partnerInInternalRealm))
    }

    @Test
    fun `sub가 principalType·principalId와 다르면 거부`() {
        assertRejected(sign(employeeClaims("sub" to "system:$EMPLOYEE_ID")))
    }

    @Test
    fun `principalId가 정규형 UUID가 아니면 거부`() {
        val upper = EMPLOYEE_ID.toString().uppercase()

        assertRejected(sign(employeeClaims("principalId" to upper, "sub" to "employee:$upper")))
    }

    @Test
    fun `principalType이 없으면 거부`() {
        assertRejected(sign(employeeClaims("principalType" to null)))
    }

    @Test
    fun `principalType이 모르는 값이면 거부`() {
        assertRejected(sign(employeeClaims("principalType" to "robot")))
    }

    @Test
    fun `roles가 없으면 거부`() {
        assertRejected(sign(employeeClaims("roles" to null)))
    }

    @Test
    fun `roles가 목록이 아니면 거부`() {
        assertRejected(sign(employeeClaims("roles" to "wms:inbound_manager")))
    }

    @Test
    fun `roles에 형식이 틀린 role이 있으면 거부`() {
        assertRejected(sign(employeeClaims("roles" to listOf("wms:Inbound-Manager"))))
    }

    private fun assertRejected(token: String) {
        assertFailsWith<JwtException> { decoder().decode(token) }
    }

    private fun decoder(
        audience: String = "wms",
        realms: Set<Realm> = setOf(Realm.INTERNAL),
        refetchMinInterval: Duration = DozyJwtDecoders.JWKS_REFETCH_MIN_INTERVAL,
    ): Decoder {
        val properties =
            DozyAuthProperties(
                audience = audience,
                acceptedRealms = realms,
                issuerBaseUri = ISSUER_BASE,
                jwkSetUri = jwks.jwkSetUri,
                clockSkew = CLOCK_SKEW,
            )
        return Decoder(decoderFor(properties, refetchMinInterval))
    }

    protected class Decoder(
        private val decode: (String) -> Jwt,
    ) {
        fun decode(token: String): Jwt = decode.invoke(token)
    }

    class Servlet : DozyJwtDecodersTest() {
        override fun decoderFor(
            properties: DozyAuthProperties,
            refetchMinInterval: Duration,
        ): (String) -> Jwt = DozyJwtDecoders.servlet(properties, FIXED_CLOCK, refetchMinInterval)::decode
    }

    class Reactive : DozyJwtDecodersTest() {
        override fun decoderFor(
            properties: DozyAuthProperties,
            refetchMinInterval: Duration,
        ): (String) -> Jwt {
            val decoder = DozyReactiveJwtDecoders.create(properties, FIXED_CLOCK, refetchMinInterval)
            return { token -> checkNotNull(decoder.decode(token).block()) }
        }
    }

    private companion object {
        /** 테스트에서 설정하는 시계 오차. 기본값은 `DozyAuthPropertiesTest`에서 확인합니다. */
        val CLOCK_SKEW: Duration = Duration.ofSeconds(30)
    }
}
