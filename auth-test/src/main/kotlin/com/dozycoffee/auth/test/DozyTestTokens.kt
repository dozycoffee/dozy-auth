package com.dozycoffee.auth.test

import com.dozycoffee.auth.core.AccessTokenFormat
import com.dozycoffee.auth.core.ClaimNames
import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.starter.DozyAuthProperties
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimNames
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 통합 테스트에서 실제 검증 체인을 거치는 토큰을 만듭니다 (starter.md §7.2).
 *
 * 테스트 컨텍스트에 빈으로 등록되며, `iss`와 기본 `aud`는 서비스 설정(`dozy.auth.*`)을 따릅니다. 형식은 token.md §3과 같습니다.
 *
 * ```kotlin
 * @Autowired lateinit var tokens: DozyTestTokens
 *
 * val token = tokens.issue(roles = listOf("sample:item_manager"))
 * val expired = tokens.issue(expiresAt = Instant.now().minusSeconds(60))
 * ```
 */
public class DozyTestTokens(
    private val properties: DozyAuthProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 서명에 쓸 키. */
    public enum class Key {
        /** 테스트 컨텍스트의 스타터가 믿는 키. */
        TRUSTED,

        /** 스타터가 믿지 않는 키. 서명 검증 실패 테스트용입니다. */
        UNTRUSTED,
    }

    /**
     * 서명된 access token을 만듭니다. 기본값은 이 서비스가 받는 정상 토큰이며, 실패 경우는 인자를 바꿔 만듭니다.
     *
     * @param type principal type
     * @param id principal id
     * @param roles `{audience}:{code}` 형식
     * @param realm `iss`의 realm. 기본은 [type]이 속한 realm. 다른 realm을 주면 DOM-01 위반 토큰이 됩니다
     * @param audience `aud`. 기본은 이 서비스의 audience
     * @param issuerBaseUri `iss`의 기준 주소. 기본은 이 서비스의 `issuer-base-uri`
     * @param issuedAt `iat`
     * @param expiresAt `exp`. 기본은 [issuedAt]에서 [ACCESS_TOKEN_TTL] 뒤
     * @param sessionId `sid`. 기본은 system token이 아니면 임의의 UUID
     * @param signedBy 서명 키
     */
    public fun issue(
        type: PrincipalType = PrincipalType.EMPLOYEE,
        id: UUID = DEFAULT_ID,
        roles: List<String> = emptyList(),
        realm: Realm = type.realm,
        audience: List<String> = listOf(properties.audience),
        issuerBaseUri: String = properties.issuerBaseUri,
        issuedAt: Instant = clock.instant(),
        expiresAt: Instant = issuedAt.plus(ACCESS_TOKEN_TTL),
        sessionId: String? = if (type == PrincipalType.SYSTEM) null else UUID.randomUUID().toString(),
        signedBy: Key = Key.TRUSTED,
    ): String {
        val key = PrincipalKey(type, id)
        val claims =
            buildMap<String, Any> {
                put(JWTClaimNames.ISSUER, realm.issuer(issuerBaseUri))
                put(JWTClaimNames.SUBJECT, key.sub)
                put(JWTClaimNames.AUDIENCE, audience)
                put(JWTClaimNames.ISSUED_AT, issuedAt.epochSecond)
                put(JWTClaimNames.EXPIRATION_TIME, expiresAt.epochSecond)
                put(JWTClaimNames.JWT_ID, UUID.randomUUID().toString())
                put(ClaimNames.PRINCIPAL_TYPE, type.claimValue)
                put(ClaimNames.PRINCIPAL_ID, id.toString())
                put(ClaimNames.ROLES, roles)
                sessionId?.let { put(ClaimNames.SID, it) }
            }

        val signingKey =
            when (signedBy) {
                Key.TRUSTED -> TestSigningKeys.TRUSTED
                Key.UNTRUSTED -> TestSigningKeys.UNTRUSTED
            }
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.parse(AccessTokenFormat.ALGORITHM))
                .type(JOSEObjectType(AccessTokenFormat.TYPE))
                .keyID(signingKey.keyID)
                .build()
        return JWSObject(header, Payload(claims)).apply { sign(RSASSASigner(signingKey)) }.serialize()
    }

    public companion object {
        /** 기본 principal id. [WithDozyPrincipal]의 기본값과 같습니다. */
        public val DEFAULT_ID: UUID = UUID.fromString(WithDozyPrincipal.DEFAULT_ID)

        /** 토큰 수명. Auth의 `policy.access-token-ttl`과 같습니다. */
        public val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(10)
    }
}
