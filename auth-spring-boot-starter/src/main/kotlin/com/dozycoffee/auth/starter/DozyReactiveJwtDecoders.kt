package com.dozycoffee.auth.starter

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.RemoteKeySourceException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Clock

/**
 * WebFlux용 디코더를 만듭니다 (starter.md §3). 서명 검증과 검증기, 인자의 뜻은 [DozyJwtDecoders.create]와 같습니다.
 *
 * JWKS 조회가 블로킹 HTTP라서 이벤트 루프를 막지 않도록 서명 검증을 `boundedElastic` 스케줄러에서 실행합니다.
 * Spring MVC 서비스에는 Reactor가 없을 수 있어서 [DozyJwtDecoders]와 파일을 나눕니다.
 */
public object DozyReactiveJwtDecoders {
    public fun create(
        properties: DozyAuthProperties,
        jwkSource: JWKSource<SecurityContext> = DozyJwtDecoders.remoteJwkSource(properties.resolvedJwkSetUri),
        clock: Clock = Clock.systemUTC(),
    ): ReactiveJwtDecoder {
        val processor = DozyJwtDecoders.processor(jwkSource)
        return NimbusReactiveJwtDecoder { jwt ->
            Mono
                .fromCallable { processor.process(jwt, null) }
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(::toJwtException)
        }.apply { setJwtValidator(DozyTokenValidators.create(properties, clock)) }
    }

    /**
     * Spring MVC 디코더(`NimbusJwtDecoder`)와 같게 예외를 나눕니다. 토큰이 잘못된 경우(서명 불일치, 모르는 키 등)는 [BadJwtException]이라
     * 401이고, 공개키를 가져오지 못한 경우는 [JwtException]이라 서버 오류입니다. 나누지 않으면 잘못된 토큰도 500이 됩니다.
     */
    private fun toJwtException(error: Throwable): Throwable =
        when (error) {
            is JwtException -> error
            is RemoteKeySourceException, is JOSEException -> JwtException(DECODING_ERROR + error.message, error)
            else -> BadJwtException(DECODING_ERROR + error.message, error)
        }

    private const val DECODING_ERROR = "An error occurred while attempting to decode the Jwt: "
}
