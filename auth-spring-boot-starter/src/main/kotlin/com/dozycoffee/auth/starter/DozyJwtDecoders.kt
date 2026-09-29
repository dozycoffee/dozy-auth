package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AccessTokenFormat
import com.dozycoffee.auth.core.Jwks
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.jwk.source.RateLimitReachedException
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
 * Spring MVC용 디코더를 만듭니다 (starter.md §3). token.md §6의 2~9를 검증하며, WebFlux용([DozyReactiveJwtDecoders])도 같은 서명 검증과
 * 같은 검증기를 씁니다.
 *
 * - `alg`는 RS256만 받습니다 (2). `typ`과 claim 검증(3, 6~9)은 [DozyTokenValidators]가 합니다.
 * - 기본 키 출처는 [DozyAuthProperties]의 JWKS 주소입니다. 캐시하고, 모르는 `kid`가 오면 다시 받되 [JWKS_REFETCH_MIN_INTERVAL]보다 자주 받지 않습니다 (4).
 *   Spring의 `withJwkSetUri` 빌더는 재조회 간격 제한을 끄므로 JWK source를 직접 만듭니다.
 *
 * 자동 설정이 만드는 디코더와 다른 키 출처가 필요할 때(테스트 키, Auth 서버의 메모리 키) 직접 호출합니다.
 */
public object DozyJwtDecoders {
    /**
     * `policy.jwks-refetch-min-interval`. 모르는 `kid`로 JWKS를 다시 받는 최소 간격입니다.
     * 임의의 `kid`로 Auth에 요청을 몰아 보내는 것을 막습니다. 서비스만 쓰는 값이라 스타터에 둡니다.
     */
    internal val JWKS_REFETCH_MIN_INTERVAL: Duration = Duration.ofSeconds(30)

    private val HTTP_TIMEOUT: Duration = Duration.ofSeconds(5)

    /**
     * @param properties 검증 규칙에 쓰는 설정 (audience, 허용 realm, issuer 기준 주소, 시계 오차)
     * @param jwkSource 서명 검증에 쓸 공개키 출처. 기본은 JWKS 주소에서 받기
     * @param clock `exp`·`iat` 검증 기준 시각
     */
    public fun create(
        properties: DozyAuthProperties,
        jwkSource: JWKSource<SecurityContext> = remoteJwkSource(properties.resolvedJwkSetUri),
        clock: Clock = Clock.systemUTC(),
    ): JwtDecoder = NimbusJwtDecoder(processor(jwkSource)).apply { setJwtValidator(DozyTokenValidators.create(properties, clock)) }

    internal fun processor(jwkSource: JWKSource<SecurityContext>): JWTProcessor<SecurityContext> =
        DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.parse(AccessTokenFormat.ALGORITHM), jwkSource)
            // typ과 claim은 Spring 검증기에서 봅니다
            setJWSTypeVerifier { _, _ -> }
            setJWTClaimsSetVerifier { _, _ -> }
        }

    /**
     * JWKS 주소에서 공개키를 받는 출처. 캐시하고 재조회 간격을 제한합니다.
     *
     * 재조회 제한에 걸리면 Nimbus는 [RateLimitReachedException]을 던지는데, Spring은 이를 공개키를 못 가져온 서버 오류(500)로 다룹니다.
     * 제한 때문에 다시 받지 못한 것은 "모르는 `kid`"이므로 키가 없는 것으로 처리해 401이 되게 합니다 (token.md §6의 4).
     */
    internal fun remoteJwkSource(
        jwkSetUri: String,
        refetchMinInterval: Duration = JWKS_REFETCH_MIN_INTERVAL,
    ): JWKSource<SecurityContext> {
        val timeout = HTTP_TIMEOUT.toMillis().toInt()
        val source =
            JWKSourceBuilder
                .create<SecurityContext>(URI.create(jwkSetUri).toURL(), DefaultResourceRetriever(timeout, timeout))
                .cache(Jwks.CACHE_MAX_AGE.toMillis(), HTTP_TIMEOUT.toMillis())
                .rateLimited(refetchMinInterval.toMillis())
                .refreshAheadCache(false)
                .retrying(false)
                .build()
        return JWKSource { selector, context ->
            try {
                source.get(selector, context)
            } catch (_: RateLimitReachedException) {
                emptyList()
            }
        }
    }
}
