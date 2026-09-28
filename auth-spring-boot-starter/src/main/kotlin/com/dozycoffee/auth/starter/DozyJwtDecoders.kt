package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AccessTokenFormat
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * token.md §6의 2~9를 검증하는 [JwtDecoder]를 만듭니다.
 *
 * - `alg`는 RS256만 받습니다 (2). `typ`과 claim 검증(3, 6~9)은 [DozyTokenValidators]가 합니다.
 * - JWKS는 캐시하고, 모르는 `kid`가 오면 다시 받습니다 (4). 재조회는 [JWKS_REFETCH_MIN_INTERVAL]보다 자주 하지 않습니다.
 *   Spring의 `NimbusJwtDecoder.withJwkSetUri`는 재조회 간격 제한을 끄므로 JWK source를 직접 만듭니다.
 */
internal object DozyJwtDecoders {
    /** JWKS 캐시 유지 시간. Auth의 JWKS 응답 캐시 시간(`policy.jwks-cache-max-age`)과 맞춥니다. */
    val JWKS_CACHE_TTL: Duration = Duration.ofMinutes(5)

    /** 모르는 `kid`로 JWKS를 다시 받는 최소 간격. 임의의 `kid`로 Auth에 요청을 몰아 보내는 것을 막습니다. */
    val JWKS_REFETCH_MIN_INTERVAL: Duration = Duration.ofSeconds(30)

    private val HTTP_TIMEOUT: Duration = Duration.ofSeconds(5)

    fun create(
        properties: DozyAuthProperties,
        clock: Clock,
        refetchMinInterval: Duration = JWKS_REFETCH_MIN_INTERVAL,
    ): JwtDecoder = create(jwkSource(properties.resolvedJwkSetUri, refetchMinInterval), properties, clock)

    fun create(
        jwkSource: JWKSource<SecurityContext>,
        properties: DozyAuthProperties,
        clock: Clock,
    ): JwtDecoder {
        val processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.parse(AccessTokenFormat.ALGORITHM), jwkSource)
                // typ과 claim은 Spring 검증기에서 봅니다
                setJWSTypeVerifier { _, _ -> }
                setJWTClaimsSetVerifier { _, _ -> }
            }
        return NimbusJwtDecoder(processor).apply { setJwtValidator(DozyTokenValidators.create(properties, clock)) }
    }

    private fun jwkSource(
        jwkSetUri: String,
        refetchMinInterval: Duration,
    ): JWKSource<SecurityContext> {
        val timeout = HTTP_TIMEOUT.toMillis().toInt()
        return JWKSourceBuilder
            .create<SecurityContext>(URI.create(jwkSetUri).toURL(), DefaultResourceRetriever(timeout, timeout))
            .cache(JWKS_CACHE_TTL.toMillis(), HTTP_TIMEOUT.toMillis())
            .rateLimited(refetchMinInterval.toMillis())
            .refreshAheadCache(false)
            .retrying(false)
            .build()
    }
}
