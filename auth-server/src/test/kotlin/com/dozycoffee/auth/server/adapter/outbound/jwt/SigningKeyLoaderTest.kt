package com.dozycoffee.auth.server.adapter.outbound.jwt

import com.dozycoffee.auth.server.domain.AuthPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SigningKeyLoaderTest {
    @TempDir
    lateinit var dir: Path

    private val clock = Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
    private val loader = SigningKeyLoader(clock)

    @Test
    fun `폴더의 모든 키를 공개키로 게시하고 활성 키로 서명`() {
        dir.resolve("dozy-2026-09.pem").writeText(KEY_A)
        dir.resolve("dozy-2027-09.pem").writeText(KEY_B)

        val keys = loader.load(SigningKeyProperties(dir, activeKid = "dozy-2026-09"))

        assertEquals("dozy-2026-09", keys.active.keyID)
        assertTrue(keys.active.isPrivate)
        assertEquals(listOf("dozy-2026-09", "dozy-2027-09"), keys.published.map { it.keyID })
        assertTrue(keys.published.none { it.isPrivate })
    }

    @Test
    fun `pem이 아닌 파일은 무시`() {
        dir.resolve("dozy-2026-09.pem").writeText(KEY_A)
        dir.resolve("README.txt").writeText("메모")

        val keys = loader.load(SigningKeyProperties(dir, activeKid = "dozy-2026-09"))

        assertEquals(1, keys.published.size)
    }

    @Test
    fun `폴더가 없으면 기동 실패`() {
        assertFailsWith<IllegalStateException> {
            loader.load(SigningKeyProperties(dir.resolve("missing"), activeKid = "dozy-2026-09"))
        }
    }

    @Test
    fun `활성 키 파일이 없으면 기동 실패`() {
        dir.resolve("dozy-2026-09.pem").writeText(KEY_A)

        assertFailsWith<IllegalStateException> {
            loader.load(SigningKeyProperties(dir, activeKid = "dozy-2027-09"))
        }
    }

    @Test
    fun `자동 생성이 아니면 활성 키를 지정해야 함`() {
        dir.resolve("dozy-2026-09.pem").writeText(KEY_A)

        assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir)) }
    }

    @Test
    fun `3072비트보다 작은 키는 기동 실패`() {
        dir.resolve("dozy-2026-09.pem").writeText(SigningKeyPem.generate(2048, SecureRandom()))

        val error = assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir, activeKid = "dozy-2026-09")) }
        assertTrue(error.message!!.contains("3072"))
    }

    @Test
    fun `kid 형식이 dozy-연도-월이 아니면 기동 실패`() {
        dir.resolve("2026-09.pem").writeText(KEY_A)

        assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir, activeKid = "2026-09")) }
    }

    @Test
    fun `PKCS8 PEM이 아니면 기동 실패`() {
        dir.resolve("dozy-2026-09.pem").writeText("not a key")

        assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir, activeKid = "dozy-2026-09")) }
    }

    @Test
    fun `자동 생성은 빈 폴더에 현재 연월 kid로 만들고 다음 기동부터 재사용`() {
        val keysDir = dir.resolve("signing-keys")
        val properties = SigningKeyProperties(keysDir, autoGenerate = true)

        val first = loader.load(properties)
        val saved = keysDir.resolve("dozy-2026-09.pem").readText()
        val second = SigningKeyLoader(Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)).load(properties)

        assertEquals("dozy-2026-09", first.active.keyID)
        assertEquals(first.active.toPublicJWK(), second.active.toPublicJWK())
        assertEquals(saved, keysDir.resolve("dozy-2026-09.pem").readText())
    }

    @Test
    fun `자동 생성은 지정한 kid의 키가 없을 때만 만듦`() {
        dir.resolve("dozy-2026-09.pem").writeText(KEY_A)

        val keys = loader.load(SigningKeyProperties(dir, activeKid = "dozy-2026-10", autoGenerate = true))

        assertEquals("dozy-2026-10", keys.active.keyID)
        assertEquals(listOf("dozy-2026-09", "dozy-2026-10"), keys.published.map { it.keyID })
    }

    @Test
    fun `자동 생성한 키 파일은 소유자만 읽고 쓸 수 있음`() {
        loader.load(SigningKeyProperties(dir, autoGenerate = true))

        val file = dir.resolve("dozy-2026-09.pem")
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
        assertFalse(file.readText().contains("RSA PRIVATE KEY"))
    }

    companion object {
        private val KEY_A = SigningKeyPem.generate(AuthPolicy.SIGNING_KEY_SIZE, SecureRandom())
        private val KEY_B = SigningKeyPem.generate(AuthPolicy.SIGNING_KEY_SIZE, SecureRandom())
    }
}
