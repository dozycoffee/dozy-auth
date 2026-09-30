package com.dozycoffee.auth.test

import com.dozycoffee.auth.starter.DozyAuthProperties
import com.dozycoffee.auth.starter.DozyJwtDecoders
import com.dozycoffee.auth.starter.DozyReactiveJwtDecoders
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.time.Clock

/**
 * 테스트 컨텍스트에서 스타터 디코더가 테스트 키를 믿게 합니다 (starter.md §7.2).
 *
 * 자동 설정이 아니라 [DozyTestContextCustomizerFactory]가 Spring 테스트 컨텍스트에만 등록합니다. 그래서 auth-test가 운영 classpath에
 * 들어가도 적용되지 않습니다. 사용자 설정처럼 자동 설정보다 먼저 처리되므로, 스타터의 기본 디코더(JWKS 주소 조회)는
 * `@ConditionalOnMissingBean`으로 빠집니다. 검증 규칙은 스타터와 같습니다.
 *
 * 스타터처럼 웹 애플리케이션에서만 켜집니다. 서비스가 디코더 빈을 직접 정의했으면 이 설정의 디코더는 빠집니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@EnableConfigurationProperties(DozyAuthProperties::class)
internal class DozyTestConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun dozyTestTokens(
        properties: DozyAuthProperties,
        clock: ObjectProvider<Clock>,
    ): DozyTestTokens = DozyTestTokens(properties, clock.getIfUnique { Clock.systemUTC() })

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    class Servlet {
        @Bean
        @ConditionalOnMissingBean
        fun dozyTestJwtDecoder(
            properties: DozyAuthProperties,
            clock: ObjectProvider<Clock>,
        ): JwtDecoder = DozyJwtDecoders.create(properties, trustedKeys(), clock.getIfUnique { Clock.systemUTC() })
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    class Reactive {
        @Bean
        @ConditionalOnMissingBean
        fun dozyTestReactiveJwtDecoder(
            properties: DozyAuthProperties,
            clock: ObjectProvider<Clock>,
        ): ReactiveJwtDecoder = DozyReactiveJwtDecoders.create(properties, trustedKeys(), clock.getIfUnique { Clock.systemUTC() })
    }
}

private fun trustedKeys(): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(TestSigningKeys.TRUSTED.toPublicJWK()))
