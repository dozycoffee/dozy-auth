package com.dozycoffee.auth.server.config.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * CORS 허용 origin (`AUTH_CORS_ALLOWED_ORIGINS`, configuration.md §1, api/conventions.md §7).
 *
 * 쿠키를 주고받으므로(`Access-Control-Allow-Credentials: true`) 와일드카드는 쓸 수 없고, 잘못된 값이면 모든 프로필에서 기동에 실패합니다.
 */
@ConfigurationProperties(prefix = "dozy.auth.cors")
data class CorsProperties(
    /** 허용할 origin. `scheme://host[:port]` 형식이며 경로는 붙이지 않습니다. */
    val allowedOrigins: List<String> = emptyList(),
) {
    init {
        require(allowedOrigins.isNotEmpty()) { "dozy.auth.cors.allowed-origins(AUTH_CORS_ALLOWED_ORIGINS)를 설정해야 합니다" }
        allowedOrigins.forEach { origin ->
            require('*' !in origin) { "dozy.auth.cors.allowed-origins에 와일드카드를 쓸 수 없습니다: '$origin'" }
            require(ORIGIN.matches(origin)) { "dozy.auth.cors.allowed-origins는 scheme://host[:port] 형식이어야 합니다: '$origin'" }
        }
    }

    private companion object {
        val ORIGIN = Regex("https?://[^/\\s]+")
    }
}
