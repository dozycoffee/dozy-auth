package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 토큰 발급 설정. 서비스(application)는 config를 참조할 수 없으므로 domain 타입 빈으로 주입합니다.
 */
@Configuration(proxyBeanMethods = false)
class TokenConfig {
    /** `AUTH_ISSUER_BASE_URL` (configuration.md §1). 없으면 기동에 실패합니다. */
    @Bean
    fun issuerBaseUri(
        @Value("\${dozy.auth.issuer-base-url}") value: String,
    ): IssuerBaseUri = IssuerBaseUri(value)
}
