package com.dozycoffee.auth.test

import com.dozycoffee.auth.test.reactivesample.ReactiveSampleApplication
import com.dozycoffee.auth.test.servletsample.ServletSampleApplication
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * auth-test가 classpath에 있어도, Spring 테스트가 아니라 애플리케이션을 직접 실행한 컨텍스트에서는 테스트 키 설정이 켜지지 않는지 확인합니다.
 * 운영 classpath에 실수로 들어간 경우와 같습니다. 이 클래스는 일부러 Spring 테스트 애노테이션 없이 컨텍스트를 직접 띄웁니다.
 */
class NotInApplicationContextTest {
    @Test
    fun `WebFlux 앱을 직접 실행하면 스타터의 기본 디코더를 쓰고 테스트 토큰 빈이 없음`() {
        run(ReactiveSampleApplication::class.java, WebApplicationType.REACTIVE).use { context ->
            assertEquals(setOf("dozyReactiveJwtDecoder"), context.getBeanNamesForType(ReactiveJwtDecoder::class.java).toSet())
            assertTrue(context.getBeanNamesForType(DozyTestTokens::class.java).isEmpty())
        }
    }

    @Test
    fun `Spring MVC 앱을 직접 실행하면 스타터의 기본 디코더를 쓰고 테스트 토큰 빈이 없음`() {
        run(ServletSampleApplication::class.java, WebApplicationType.SERVLET).use { context ->
            assertEquals(setOf("dozyJwtDecoder"), context.getBeanNamesForType(JwtDecoder::class.java).toSet())
            assertTrue(context.getBeanNamesForType(DozyTestTokens::class.java).isEmpty())
        }
    }

    private fun run(
        application: Class<*>,
        type: WebApplicationType,
    ) = SpringApplicationBuilder(application).web(type).properties("server.port=0").run()
}
