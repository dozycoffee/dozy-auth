package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.outbound.jwt.SigningKeys
import com.dozycoffee.auth.starter.DozyAuthProperties
import com.dozycoffee.auth.starter.DozyJwtDecoders
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Clock

/**
 * Auth 서버가 받는 토큰의 검증기 (ADR-0031). 검증 규칙은 서비스와 같은 스타터 디코더를 쓰고, 공개키는 HTTP로 받지 않고
 * 서버가 가진 메모리 키(JWKS에 게시하는 키 전부)를 씁니다.
 *
 * 경로마다 `aud` 검사가 달라(api/conventions.md §2) 검증기를 두 개 만듭니다. 이 빈이 있으면 스타터의 기본 디코더는 만들어지지 않습니다.
 * 설정(`dozy.auth.*`)은 스타터의 [DozyAuthProperties]입니다: audience `auth`, 허용 realm `internal`.
 */
@Configuration(proxyBeanMethods = false)
class TokenVerificationConfig {
    /** `/admin/...`, `/internal/...`: token.md §6 전부. `aud`에 `auth`가 있어야 합니다. */
    @Bean(AUTH_AUDIENCE_DECODER)
    fun authAudienceJwtDecoder(
        properties: DozyAuthProperties,
        signingKeys: SigningKeys,
        clock: Clock,
    ): JwtDecoder = DozyJwtDecoders.create(properties, publishedKeys(signingKeys), clock)

    /** `/realms/{realm}/...` 본인 API: `aud`만 검사하지 않습니다. role이 없는 직원의 토큰도 받습니다 (token.md §4). */
    @Bean(USER_DECODER)
    fun userJwtDecoder(
        properties: DozyAuthProperties,
        signingKeys: SigningKeys,
        clock: Clock,
    ): JwtDecoder = DozyJwtDecoders.createWithoutAudienceCheck(properties, publishedKeys(signingKeys), clock)

    private fun publishedKeys(signingKeys: SigningKeys): JWKSource<SecurityContext> = ImmutableJWKSet(JWKSet(signingKeys.published))

    companion object {
        const val AUTH_AUDIENCE_DECODER = "authAudienceJwtDecoder"
        const val USER_DECODER = "userJwtDecoder"
    }
}
