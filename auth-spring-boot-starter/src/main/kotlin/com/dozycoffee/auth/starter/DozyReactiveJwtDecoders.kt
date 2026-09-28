package com.dozycoffee.auth.starter

import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.time.Duration

/**
 * WebFlux용 디코더. 서명 검증과 검증기는 [DozyJwtDecoders]와 같습니다.
 *
 * JWKS 조회가 블로킹 HTTP라서 이벤트 루프를 막지 않도록 서명 검증을 `boundedElastic` 스케줄러에서 실행합니다.
 * Spring MVC 서비스에는 Reactor가 없을 수 있어서 [DozyJwtDecoders]와 파일을 나눕니다.
 */
internal object DozyReactiveJwtDecoders {
    fun create(
        properties: DozyAuthProperties,
        clock: Clock,
        refetchMinInterval: Duration = DozyJwtDecoders.JWKS_REFETCH_MIN_INTERVAL,
    ): ReactiveJwtDecoder {
        val processor = DozyJwtDecoders.processor(DozyJwtDecoders.jwkSource(properties.resolvedJwkSetUri, refetchMinInterval))
        return NimbusReactiveJwtDecoder { jwt ->
            Mono.fromCallable { processor.process(jwt, null) }.subscribeOn(Schedulers.boundedElastic())
        }.apply { setJwtValidator(DozyTokenValidators.create(properties, clock)) }
    }
}
