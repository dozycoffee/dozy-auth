package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AccessTokenFormat
import com.dozycoffee.auth.core.Jwks
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.proc.JWTProcessor
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * token.md §6의 2~9를 검증하는 디코더를 만듭니다. WebFlux용([DozyReactiveJwtDecoders])도 같은 서명 검증과 같은 검증기를 씁니다.
 *
 * - `alg`는 RS256만 받습니다 (2). `typ`과 claim 검증(3, 6~9)은 [DozyTokenValidators]가 합니다.
 * - JWKS는 캐시하고, 모르는 `kid`가 오면 다시 받습니다 (4). 재조회는 [JWKS_REFETCH_MIN_INTERVAL]보다 자주 하지 않습니다.
 *   Spring의 `withJwkSetUri` 빌더는 재조회 간격 제한을 끄므로 JWK source를 직접 만듭니다.
 */
internal object DozyJwtDecoders {
    /**
     * `policy.jwks-refetch-min-interval`. 모르는 `kid`로 JWKS를 다시 받는 최소 간격입니다.
     * 임의의 `kid`로 Auth에 요청을 몰아 보내는 것을 막습니다. 서비스만 쓰는 값이라 스타터에 둡니다.
     */
    val JWKS_REFETCH_MIN_INTERVAL: Duration = Duration.ofSeconds(30)

    private val HTTP_TIMEOUT: Duration = Duration.ofSeconds(5)

    fun servlet(
        properties: DozyAuthProperties,
        clock: Clock,
        refetchMinInterval: Duration = JWKS_REFETCH_MIN_INTERVAL,
    ): JwtDecoder =
        NimbusJwtDecoder(processor(jwkSource(properties.resolvedJwkSetUri, refetchMinInterval))).apply {
            setJwtValidator(DozyTokenValidators.create(properties, clock))
        }

    fun processor(jwkSource: JWKSource<SecurityContext>): JWTProcessor<SecurityContext> =
        DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.parse(AccessTokenFormat.ALGORITHM), jwkSource)
            // typ과 claim은 Spring 검증기에서 봅니다
            setJWSTypeVerifier { _, _ -> }
            setJWTClaimsSetVerifier { _, _ -> }
        }

    fun jwkSource(
        jwkSetUri: String,
        refetchMinInterval: Duration,
    ): JWKSource<SecurityContext> {
        val timeout = HTTP_TIMEOUT.toMillis().toInt()
        return JWKSourceBuilder
            .create<SecurityContext>(URI.create(jwkSetUri).toURL(), DefaultResourceRetriever(timeout, timeout))
            .cache(Jwks.CACHE_MAX_AGE.toMillis(), HTTP_TIMEOUT.toMillis())
            .rateLimited(refetchMinInterval.toMillis())
            .refreshAheadCache(false)
            .retrying(false)
            .build()
    }
}
