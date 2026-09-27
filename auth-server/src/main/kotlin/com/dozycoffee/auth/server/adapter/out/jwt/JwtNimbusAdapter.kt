package com.dozycoffee.auth.server.adapter.out.jwt

import com.dozycoffee.auth.core.AccessTokenFormat
import com.dozycoffee.auth.core.ClaimNames
import com.dozycoffee.auth.server.application.port.out.jwt.LoadJwksPort
import com.dozycoffee.auth.server.application.port.out.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component

/** access token 서명과 JWKS 게시 (token.md §2, §3, §7). 서명은 활성 키 하나로만 합니다. */
@Component
class JwtNimbusAdapter(
    private val keys: SigningKeys,
) : SignTokenPort,
    LoadJwksPort {
    private val encoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(keys.active)))

    override fun sign(claims: AccessTokenClaims): String {
        val header =
            JwsHeader
                .with(ALGORITHM)
                .type(AccessTokenFormat.TYPE)
                .keyId(keys.active.keyID)
                .build()

        val body =
            JwtClaimsSet
                .builder()
                .issuer(claims.issuer)
                .subject(claims.principal.sub)
                .audience(claims.audience)
                .issuedAt(claims.issuedAt)
                .expiresAt(claims.expiresAt)
                .id(claims.tokenId)
                .claim(ClaimNames.PRINCIPAL_TYPE, claims.principal.type.claimValue)
                .claim(ClaimNames.PRINCIPAL_ID, claims.principal.id.toString())
                .claim(ClaimNames.ROLES, claims.roles)
                .apply { claims.sessionId?.let { claim(ClaimNames.SID, it) } }
                .build()

        return encoder.encode(JwtEncoderParameters.from(header, body)).tokenValue
    }

    override fun load(): Map<String, Any> = JWKSet(keys.published).toJSONObject(true)

    private companion object {
        val ALGORITHM: SignatureAlgorithm = checkNotNull(SignatureAlgorithm.from(AccessTokenFormat.ALGORITHM))
    }
}
