package com.dozycoffee.auth.test

import com.dozycoffee.auth.starter.DozyAuthProperties
import com.dozycoffee.auth.starter.DozyJwtDecoders
import com.dozycoffee.auth.starter.DozyReactiveJwtDecoders
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import java.time.Clock

/**
 * 테스트 classpath에 auth-test가 있으면 스타터 디코더가 테스트 키를 믿게 합니다 (starter.md §7.2).
 *
 * 스타터 자동 설정보다 먼저 디코더 빈을 등록해 스타터의 기본 디코더(JWKS 주소 조회)를 대신합니다. 검증 규칙은 스타터와 같습니다.
 * 서비스가 디코더 빈을 직접 정의했으면 이 설정은 빠집니다.
 */
@AutoConfiguration(
    beforeName = [
        "com.dozycoffee.auth.starter.DozyAuthServletAutoConfiguration",
        "com.dozycoffee.auth.starter.DozyAuthReactiveAutoConfiguration",
    ],
)
@EnableConfigurationProperties(DozyAuthProperties::class)
public class DozyTestAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun dozyTestTokens(
        properties: DozyAuthProperties,
        clock: ObjectProvider<Clock>,
    ): DozyTestTokens = DozyTestTokens(properties, clock.getIfUnique { Clock.systemUTC() })

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public class Servlet {
        @Bean
        @ConditionalOnMissingBean
        public fun dozyTestJwtDecoder(
            properties: DozyAuthProperties,
            clock: ObjectProvider<Clock>,
        ): JwtDecoder = DozyJwtDecoders.create(properties, trustedKeys(), clock.getIfUnique { Clock.systemUTC() })
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public class Reactive {
        @Bean
        @ConditionalOnMissingBean
        public fun dozyTestReactiveJwtDecoder(
            properties: DozyAuthProperties,
            clock: ObjectProvider<Clock>,
        ): ReactiveJwtDecoder = DozyReactiveJwtDecoders.create(properties, trustedKeys(), clock.getIfUnique { Clock.systemUTC() })
    }
}

private fun trustedKeys(): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(TestSigningKeys.TRUSTED.toPublicJWK()))
