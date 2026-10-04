package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.outbound.jwt.SigningKeyProperties
import com.dozycoffee.auth.server.support.TestSigningKeys
import com.dozycoffee.auth.server.support.TokenFixtures.FIXED_CLOCK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
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
            JwtConfig().signingKeys(
                SigningKeyProperties(dir.resolve("missing"), activeKid = TestSigningKeys.CURRENT_KID),
                Clock.systemUTC(),
                prod,
            )
        }
    }

    @Test
    fun `local 프로필에서 active-kid를 빈 값으로 두면 지정하지 않은 것처럼 키를 자동 생성`() {
        val local =
            MockEnvironment().apply {
                setActiveProfiles("local")
                setProperty("dozy.auth.signing.keys-dir", dir.toString())
                setProperty("dozy.auth.signing.active-kid", "")
                setProperty("dozy.auth.signing.auto-generate", "true")
            }
        val properties =
            Binder(ConfigurationPropertySources.get(local)).bindOrCreate("dozy.auth.signing", SigningKeyProperties::class.java)

        val keys = JwtConfig().signingKeys(properties, FIXED_CLOCK, local)

        assertEquals(listOf("${keys.active.keyID}.pem"), dir.listDirectoryEntries().map { it.name })
    }
}
