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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import java.time.Clock

/**
 * Spring MVC 서비스용 토큰 검증·인가 자동 설정 (starter.md §3). WebFlux는 [DozyAuthReactiveAutoConfiguration]입니다.
 *
 * 모든 빈은 서비스가 같은 타입의 빈을 정의하면 빠집니다. `SecurityFilterChain`만 교체할 때는 나머지 빈을 주입받아 쓰면
 * 토큰 검증 규칙은 그대로 유지됩니다.
 *
 * Spring Boot의 기본 보안 설정보다 먼저 적용되어야 Boot의 기본 사용자·필터 체인이 만들어지지 않습니다.
 */
@AutoConfiguration(
    beforeName = [
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration",
    ],
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(DozyAuthProperties::class)
public class DozyAuthServletAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public fun dozyJwtDecoder(
        properties: DozyAuthProperties,
        clock: ObjectProvider<Clock>,
    ): JwtDecoder = DozyJwtDecoders.create(properties, clock = clock.getIfUnique { Clock.systemUTC() })

    @Bean
    @ConditionalOnMissingBean(name = ["dozyJwtAuthenticationConverter"])
    public fun dozyJwtAuthenticationConverter(properties: DozyAuthProperties): Converter<Jwt, AbstractAuthenticationToken> =
        DozyJwtAuthenticationConverter(properties)

    @Bean
    @ConditionalOnMissingBean
    public fun dozyAuthenticationEntryPoint(beanFactory: BeanFactory): AuthenticationEntryPoint =
        DozyAuthenticationEntryPoint(DozyServletProblemWriter.create(beanFactory))

    @Bean
    @ConditionalOnMissingBean
    public fun dozyAccessDeniedHandler(beanFactory: BeanFactory): AccessDeniedHandler =
        DozyAccessDeniedHandler(DozyServletProblemWriter.create(beanFactory))

    @Bean(DozyAuth.BEAN_NAME)
    @ConditionalOnMissingBean
    public fun dozyAuth(): DozyAuth = DozyAuth()

    @Bean
    @ConditionalOnMissingBean
    public fun dozySecurityFilterChain(
        http: HttpSecurity,
        properties: DozyAuthProperties,
        jwtDecoder: JwtDecoder,
        dozyJwtAuthenticationConverter: Converter<Jwt, AbstractAuthenticationToken>,
        entryPoint: AuthenticationEntryPoint,
        accessDeniedHandler: AccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                properties.publicPaths.forEach { authorize(it, permitAll) }
                authorize(anyRequest, authenticated)
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
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
        }
        return http.build()
    }

    /** `@PreAuthorize` 활성화. 서비스가 직접 켜려면 `dozy.auth.method-security=false`. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dozy.auth", name = ["method-security"], havingValue = "true", matchIfMissing = true)
    @EnableMethodSecurity
    public class MethodSecurityConfiguration
}
