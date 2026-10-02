package com.dozycoffee.auth.server.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** CORS 허용 origin 설정 검사 (api/conventions.md §7, configuration.md §2). */
class CorsPropertiesTest {
    @Test
    fun `명시한 origin 목록은 그대로 받음`() {
        val origins = listOf("https://admin.dozycoffee.com", "http://localhost:3000")

        assertEquals(origins, CorsProperties(origins).allowedOrigins)
    }

    @Test
    fun `허용 origin이 없으면 기동 실패`() {
        assertFailsWith<IllegalArgumentException> { CorsProperties(emptyList()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["*", "https://*.dozycoffee.com", "admin.dozycoffee.com", "https://admin.dozycoffee.com/"])
    fun `와일드카드나 origin 형식이 아닌 값이면 기동 실패`(origin: String) {
        assertFailsWith<IllegalArgumentException> { CorsProperties(listOf(origin)) }
    }
}
