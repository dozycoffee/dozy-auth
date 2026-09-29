package com.dozycoffee.auth.test.reactivesample

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.auth.test.WithDozyPrincipal
import com.dozycoffee.auth.test.WithDozyPrincipalSecurityContextFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono
import java.util.UUID
import kotlin.test.assertEquals

/**
 * 같은 type·id·role이면 `@WithDozyPrincipal`과 `DozyTestTokens`가 같은 주체를 만드는지 확인합니다.
 * 두 경로를 따로 실행해 결과를 비교합니다 (한 요청에 둘을 섞으면 한쪽이 무시돼도 알 수 없음).
 */
@SpringBootTest(properties = ["spring.main.web-application-type=reactive"])
class SamePrincipalTest {
    @Autowired
    lateinit var beanFactory: BeanFactory

    @Autowired
    lateinit var tokens: DozyTestTokens

    @Autowired
    lateinit var decoder: ReactiveJwtDecoder

    @Autowired
    lateinit var dozyJwtAuthenticationConverter: Converter<Jwt, Mono<AbstractAuthenticationToken>>

    @Test
    fun `애노테이션과 테스트 토큰이 같은 principal과 권한을 만듦`() {
        val roles = listOf("sample:item_manager", "other:item_editor")
        val annotation = WithDozyPrincipal(type = PrincipalType.SYSTEM, id = ID, roles = roles.toTypedArray())

        val fromAnnotation =
            WithDozyPrincipalSecurityContextFactory(
                beanFactory,
            ).createSecurityContext(annotation).authentication.let(::checkNotNull)
        val token = tokens.issue(type = PrincipalType.SYSTEM, id = UUID.fromString(ID), roles = roles)
        val fromToken = checkNotNull(decoder.decode(token).flatMap { dozyJwtAuthenticationConverter.convert(it) }.block())

        assertEquals(fromToken.principal, fromAnnotation.principal)
        assertEquals(fromToken.authorities.toSet(), fromAnnotation.authorities.toSet())
    }

    private companion object {
        const val ID = "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73"
    }
}
