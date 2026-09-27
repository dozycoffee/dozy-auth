package com.dozycoffee.auth.server.adapter.outbound.jwt

import com.dozycoffee.auth.core.AccessTokenFormat
import com.dozycoffee.auth.core.ClaimNames
import com.dozycoffee.auth.server.application.port.outbound.jwt.LoadJwksPort
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimNames
import org.springframework.stereotype.Component

/**
 * access token 서명과 JWKS 게시 (token.md §2, §3, §7). 서명은 활성 키 하나로만 합니다.
 *
 * claim 본문은 직접 만듭니다. Nimbus `JWTClaimsSet`은 `aud`가 하나면 문자열로, 비어 있으면 생략해서 직렬화하는데,
 * token.md §3은 `aud`를 항상 배열로 정합니다.
 */
@Component
class JwtNimbusAdapter(
    private val keys: SigningKeys,
) : SignTokenPort,
    LoadJwksPort {
    private val signer = RSASSASigner(keys.active)

    private val header =
        JWSHeader
            .Builder(JWSAlgorithm.parse(AccessTokenFormat.ALGORITHM))
            .type(JOSEObjectType(AccessTokenFormat.TYPE))
            .keyID(keys.active.keyID)
            .build()

    override fun sign(claims: AccessTokenClaims): String {
        val body =
            buildMap<String, Any> {
                put(JWTClaimNames.ISSUER, claims.issuer)
                put(JWTClaimNames.SUBJECT, claims.principal.sub)
                put(JWTClaimNames.AUDIENCE, claims.audience)
                put(JWTClaimNames.ISSUED_AT, claims.issuedAt.epochSecond)
                put(JWTClaimNames.EXPIRATION_TIME, claims.expiresAt.epochSecond)
                put(JWTClaimNames.JWT_ID, claims.tokenId)
                put(ClaimNames.PRINCIPAL_TYPE, claims.principal.type.claimValue)
                put(ClaimNames.PRINCIPAL_ID, claims.principal.id.toString())
                put(ClaimNames.ROLES, claims.roles)
                claims.sessionId?.let { put(ClaimNames.SID, it) }
            }

        return JWSObject(header, Payload(body)).apply { sign(signer) }.serialize()
    }

    override fun load(): Map<String, Any> = JWKSet(keys.published).toJSONObject(true)
}
