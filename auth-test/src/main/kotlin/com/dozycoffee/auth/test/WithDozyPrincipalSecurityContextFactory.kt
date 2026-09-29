package com.dozycoffee.auth.test

import com.dozycoffee.auth.core.ClaimNames
import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.starter.DozyAuthProperties
import org.reactivestreams.Publisher
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.context.support.WithSecurityContextFactory
import reactor.core.publisher.Mono
import java.time.Clock
import java.util.UUID

/**
 * [WithDozyPrincipal]의 속성으로 claim만 채운 [Jwt]를 만들어 스타터의 권한 변환기(`dozyJwtAuthenticationConverter` 빈)에 넣습니다.
 * 서비스가 변환기를 교체했으면 교체한 변환기를 씁니다.
 *
 * 서명과 검증은 거치지 않습니다. 토큰 검증까지 확인하려면 [DozyTestTokens]를 씁니다.
 */
public class WithDozyPrincipalSecurityContextFactory(
    private val beanFactory: BeanFactory,
) : WithSecurityContextFactory<WithDozyPrincipal> {
    override fun createSecurityContext(annotation: WithDozyPrincipal): SecurityContext {
        val properties = beanFactory.getBean(DozyAuthProperties::class.java)
        val clock = beanFactory.getBeanProvider(Clock::class.java).getIfUnique { Clock.systemUTC() }
        val key = PrincipalKey(annotation.type, UUID.fromString(annotation.id))
        val now = clock.instant()

        val jwt =
            Jwt
                .withTokenValue("with-dozy-principal")
                .header("alg", "none")
                .issuer(annotation.type.realm.issuer(properties.issuerBaseUri))
                .subject(key.sub)
                .audience(listOf(properties.audience))
                .issuedAt(now)
                .expiresAt(now.plus(DozyTestTokens.ACCESS_TOKEN_TTL))
                .claim(ClaimNames.PRINCIPAL_TYPE, annotation.type.claimValue)
                .claim(ClaimNames.PRINCIPAL_ID, key.id.toString())
                .claim(ClaimNames.ROLES, annotation.roles.toList())
                .build()

        return SecurityContextHolder.createEmptyContext().apply { authentication = convert(jwt) }
    }

    /** 변환기는 Spring MVC에서는 인증 정보를, WebFlux에서는 `Mono`를 돌려줍니다. */
    private fun convert(jwt: Jwt): Authentication {
        @Suppress("UNCHECKED_CAST")
        val converter = beanFactory.getBean(CONVERTER_BEAN_NAME) as Converter<Jwt, *>
        return when (val result = converter.convert(jwt)) {
            is Authentication -> result
            is Publisher<*> -> checkNotNull(Mono.from(result).block() as Authentication?) { "권한 변환 결과가 비어 있습니다" }
            else -> error("알 수 없는 권한 변환 결과입니다: $result")
        }
    }

    private companion object {
        private const val CONVERTER_BEAN_NAME = "dozyJwtAuthenticationConverter"
    }
}
