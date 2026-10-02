package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import com.dozycoffee.auth.starter.DozyAuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 토큰 발급 설정. 서비스(application)는 config를 참조할 수 없으므로 domain 타입 빈으로 주입합니다.
 */
@Configuration(proxyBeanMethods = false)
class TokenConfig {
    /**
     * `AUTH_ISSUER_BASE_URL` (configuration.md §1). 속성은 스타터와 같은 `dozy.auth.issuer-base-uri` 하나라
     * 발급하는 `iss`와 검증에서 허용하는 issuer가 같은 값에서 나옵니다. 없으면 기동에 실패합니다.
     */
    @Bean
    fun issuerBaseUri(properties: DozyAuthProperties): IssuerBaseUri = IssuerBaseUri(properties.issuerBaseUri)
}
