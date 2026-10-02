package com.dozycoffee.auth.starter

import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import reactor.core.publisher.Mono
import java.time.Clock

/**
 * WebFlux 서비스용 토큰 검증·인가 자동 설정 (starter.md §3). 검증 규칙과 권한 변환은 Spring MVC용([DozyAuthServletAutoConfiguration])과 같습니다.
 *
 * 모든 빈은 서비스가 같은 타입의 빈을 정의하면 빠집니다. `SecurityWebFilterChain`만 교체할 때는 나머지 빈을 주입받아 쓰면
 * 토큰 검증 규칙은 그대로 유지됩니다.
 *
 * Spring Boot의 기본 보안 설정보다 먼저 적용되어야 Boot의 기본 사용자·필터 체인이 만들어지지 않습니다.
 */
@AutoConfiguration(
    beforeName = [
        "org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration",
    ],
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(DozyAuthProperties::class)
public class DozyAuthReactiveAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun dozyReactiveJwtDecoder(
        properties: DozyAuthProperties,
        clock: ObjectProvider<Clock>,
    ): ReactiveJwtDecoder = DozyReactiveJwtDecoders.create(properties, clock = clock.getIfUnique { Clock.systemUTC() })

    @Bean
    @ConditionalOnMissingBean(name = ["dozyJwtAuthenticationConverter"])
    public fun dozyJwtAuthenticationConverter(properties: DozyAuthProperties): Converter<Jwt, Mono<AbstractAuthenticationToken>> =
        ReactiveJwtAuthenticationConverterAdapter(DozyJwtAuthenticationConverter(properties))

    @Bean
    @ConditionalOnMissingBean
    public fun dozyServerAuthenticationEntryPoint(beanFactory: BeanFactory): ServerAuthenticationEntryPoint =
        DozyServerAuthenticationEntryPoint(DozyReactiveProblemWriter.create(beanFactory))

    @Bean
    @ConditionalOnMissingBean
    public fun dozyServerAccessDeniedHandler(beanFactory: BeanFactory): ServerAccessDeniedHandler =
        DozyServerAccessDeniedHandler(DozyReactiveProblemWriter.create(beanFactory))

    @Bean(DozyAuth.BEAN_NAME)
    @ConditionalOnMissingBean
    public fun dozyReactiveAuth(): DozyReactiveAuth = DozyReactiveAuth()

    @Bean
    @ConditionalOnMissingBean
    public fun dozySecurityWebFilterChain(
        http: ServerHttpSecurity,
        properties: DozyAuthProperties,
        jwtDecoder: ReactiveJwtDecoder,
        dozyJwtAuthenticationConverter: Converter<Jwt, Mono<AbstractAuthenticationToken>>,
        entryPoint: ServerAuthenticationEntryPoint,
        accessDeniedHandler: ServerAccessDeniedHandler,
    ): SecurityWebFilterChain =
        http {
            authorizeExchange {
                properties.publicPaths.forEach { authorize(it, permitAll) }
                authorize(anyExchange, authenticated)
            }
            oauth2ResourceServer {
                jwt {
                    this.jwtDecoder = jwtDecoder
                    jwtAuthenticationConverter = dozyJwtAuthenticationConverter
                }
                authenticationEntryPoint = entryPoint
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
            securityContextRepository = NoOpServerSecurityContextRepository.getInstance()
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
        }

    /** `@PreAuthorize` 활성화 (`suspend` 함수, `Mono`·`Flux` 반환 메서드). 서비스가 직접 켜려면 `dozy.auth.method-security=false`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dozy.auth", name = ["method-security"], havingValue = "true", matchIfMissing = true)
    @EnableReactiveMethodSecurity
    public class ReactiveMethodSecurityConfiguration
}
