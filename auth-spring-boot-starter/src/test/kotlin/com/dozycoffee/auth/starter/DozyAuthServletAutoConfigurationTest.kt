package com.dozycoffee.auth.starter

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Spring MVC 자동 설정의 등록 조건과 설정 검사 (starter.md §2, §3). */
class DozyAuthServletAutoConfigurationTest {
    private val runner =
        WebApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(
                DozyAuthServletAutoConfiguration::class.java,
                SecurityAutoConfiguration::class.java,
                ServletWebSecurityAutoConfiguration::class.java,
                UserDetailsServiceAutoConfiguration::class.java,
                OAuth2ResourceServerAutoConfiguration::class.java,
            ),
        )

    private val required =
        arrayOf(
            "dozy.auth.audience=wms",
            "dozy.auth.accepted-realms=internal",
            "dozy.auth.issuer-base-uri=https://auth.dozycoffee.com",
        )

    @Test
    fun `필수 설정이 모두 있으면 스타터 빈을 모두 등록`() {
        runner.withPropertyValues(*required).run { context ->
            assertNotNull(context.getBean(JwtDecoder::class.java))
            assertNotNull(context.getBean(SecurityFilterChain::class.java))
            assertNotNull(context.getBean("dozyAuth", DozyAuth::class.java))
            assertNotNull(context.getBean("dozyJwtAuthenticationConverter"))
        }
    }

    @Test
    fun `Spring Boot 기본 사용자를 만들지 않음`() {
        runner.withPropertyValues(*required).run { context ->
            assertTrue(context.getBeansOfType(UserDetailsService::class.java).isEmpty())
        }
    }

    @Test
    fun `audience가 없으면 기동 실패`() = assertStartupFailsWithout("dozy.auth.audience")

    @Test
    fun `accepted-realms가 없으면 기동 실패`() = assertStartupFailsWithout("dozy.auth.accepted-realms")

    @Test
    fun `issuer-base-uri가 없으면 기동 실패`() = assertStartupFailsWithout("dozy.auth.issuer-base-uri")

    @Test
    fun `jwk-set-uri를 비우면 Auth 주소의 JWKS 경로를 씀`() {
        runner.withPropertyValues(*required).run { context ->
            assertEquals(
                "https://auth.dozycoffee.com/.well-known/jwks.json",
                context.getBean(DozyAuthProperties::class.java).resolvedJwkSetUri,
            )
        }
    }

    @Test
    fun `시계 오차 기본값은 30초`() {
        runner.withPropertyValues(*required).run { context ->
            assertEquals(Duration.ofSeconds(30), context.getBean(DozyAuthProperties::class.java).clockSkew)
        }
    }

    @Test
    fun `서비스가 SecurityFilterChain을 정의하면 스타터 것은 빠지고 토큰 검증 빈은 남음`() {
        runner.withPropertyValues(*required).withUserConfiguration(CustomChain::class.java).run { context ->
            assertSame(context.getBean("customChain"), context.getBean(SecurityFilterChain::class.java))
            assertNotNull(context.getBean(JwtDecoder::class.java))
        }
    }

    @Test
    fun `서비스가 JwtDecoder를 정의하면 스타터 것은 빠짐`() {
        runner.withPropertyValues(*required).withUserConfiguration(CustomDecoder::class.java).run { context ->
            assertSame(CustomDecoder.DECODER, context.getBean(JwtDecoder::class.java))
        }
    }

    /** 필수 속성 하나를 빼고 띄우면 그 속성 이름이 담긴 이유로 실패해야 합니다. */
    private fun assertStartupFailsWithout(property: String) {
        runner.withPropertyValues(*required.filterNot { it.startsWith("$property=") }.toTypedArray()).run { context ->
            assertTrue(checkNotNull(context.startupFailure).stackTraceToString().contains(property))
        }
    }

    @Test
    fun `서비스에 Clock 빈이 없거나 여러 개여도 기동`() {
        runner.withPropertyValues(*required).run { context -> assertNotNull(context.getBean(JwtDecoder::class.java)) }
        runner.withPropertyValues(*required).withUserConfiguration(TwoClocks::class.java).run { context ->
            assertNotNull(context.getBean(JwtDecoder::class.java))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class TwoClocks {
        @Bean
        fun utcClock(): Clock = Clock.systemUTC()

        @Bean
        fun seoulClock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
    }

    @Configuration(proxyBeanMethods = false)
    class CustomChain {
        @Bean
        fun customChain(http: HttpSecurity): SecurityFilterChain = http.build()
    }

    @Configuration(proxyBeanMethods = false)
    class CustomDecoder {
        @Bean
        fun customDecoder(): JwtDecoder = DECODER

        companion object {
            val DECODER = JwtDecoder { throw UnsupportedOperationException() }
        }
    }
}
