package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.out.jwt.SigningKeyProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path
import java.time.Clock
import kotlin.test.assertFailsWith

class JwtConfigTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `prod 프로필에서 서명 키 자동 생성이 켜져 있으면 기동 실패`() {
        val prod = MockEnvironment().apply { setActiveProfiles("prod") }

        assertFailsWith<IllegalStateException> {
            JwtConfig().signingKeys(SigningKeyProperties(dir, autoGenerate = true), Clock.systemUTC(), prod)
        }
    }

    @Test
    fun `prod 프로필에서 서명 키가 없으면 기동 실패`() {
        val prod = MockEnvironment().apply { setActiveProfiles("prod") }

        assertFailsWith<IllegalStateException> {
            JwtConfig().signingKeys(SigningKeyProperties(dir.resolve("missing"), activeKid = "dozy-2026-09"), Clock.systemUTC(), prod)
        }
    }
}
