package com.dozycoffee.auth.starter

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** WebFlux 자동 설정의 등록 조건 (starter.md §3). */
class DozyAuthReactiveAutoConfigurationTest {
    private val autoConfigurations =
        AutoConfigurations.of(
            DozyAuthServletAutoConfiguration::class.java,
            DozyAuthReactiveAutoConfiguration::class.java,
            ReactiveWebSecurityAutoConfiguration::class.java,
            ReactiveUserDetailsServiceAutoConfiguration::class.java,
            ReactiveOAuth2ResourceServerAutoConfiguration::class.java,
        )

    private val required =
        arrayOf(
            "dozy.auth.audience=sample",
            "dozy.auth.accepted-realms=internal",
            "dozy.auth.issuer-base-uri=https://auth.dozycoffee.com",
        )

    @Test
    fun `WebFlux 앱에는 reactive 빈을 등록하고 Spring MVC 빈은 등록하지 않음`() {
        ReactiveWebApplicationContextRunner().withConfiguration(autoConfigurations).withPropertyValues(*required).run { context ->
            assertNotNull(context.getBean(ReactiveJwtDecoder::class.java))
            assertNotNull(context.getBean(SecurityWebFilterChain::class.java))
            assertNotNull(context.getBean("dozyAuth", DozyReactiveAuth::class.java))
            assertTrue(context.getBeansOfType(JwtDecoder::class.java).isEmpty())
        }
    }

    @Test
    fun `WebFlux 앱에서 Spring Boot 기본 사용자를 만들지 않음`() {
        ReactiveWebApplicationContextRunner().withConfiguration(autoConfigurations).withPropertyValues(*required).run { context ->
            assertTrue(context.getBeansOfType(ReactiveUserDetailsService::class.java).isEmpty())
        }
    }

    @Test
    fun `WebFlux 앱도 필수 설정이 없으면 기동 실패`() {
        ReactiveWebApplicationContextRunner().withConfiguration(autoConfigurations).run { context ->
            assertTrue(checkNotNull(context.startupFailure).stackTraceToString().contains("dozy.auth.audience"))
        }
    }

    @Test
    fun `Spring MVC 앱에는 reactive 빈을 등록하지 않음`() {
        val servletSecurity = AutoConfigurations.of(SecurityAutoConfiguration::class.java, ServletWebSecurityAutoConfiguration::class.java)

        WebApplicationContextRunner()
            .withConfiguration(
                autoConfigurations,
            ).withConfiguration(servletSecurity)
            .withPropertyValues(*required)
            .run { context ->
                assertNotNull(context.getBean(JwtDecoder::class.java))
                assertTrue(context.getBeansOfType(ReactiveJwtDecoder::class.java).isEmpty())
            }
    }
}
