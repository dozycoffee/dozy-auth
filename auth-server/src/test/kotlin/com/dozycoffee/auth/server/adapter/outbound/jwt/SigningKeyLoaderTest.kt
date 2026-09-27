package com.dozycoffee.auth.server.adapter.outbound.jwt

import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.support.TestSigningKeys
import com.dozycoffee.auth.server.support.TestSigningKeys.CURRENT_KID
import com.dozycoffee.auth.server.support.TestSigningKeys.NEXT_KID
import com.dozycoffee.auth.server.support.TokenFixtures.FIXED_CLOCK
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.ZoneOffset
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** configuration.md §2, §3. */
class SigningKeyLoaderTest {
    @TempDir
    lateinit var dir: Path

    private val loader = SigningKeyLoader(FIXED_CLOCK)

    @Test
    fun `폴더의 모든 키를 공개키로 게시하고 활성 키로 서명`() {
        TestSigningKeys.writeCurrentAndNext(dir)

        val keys = loader.load(SigningKeyProperties(dir, activeKid = CURRENT_KID))

        assertEquals(CURRENT_KID, keys.active.keyID)
        assertTrue(keys.active.isPrivate)
        assertEquals(listOf(CURRENT_KID, NEXT_KID), keys.published.map { it.keyID })
        assertTrue(keys.published.none { it.isPrivate })
    }

    @Test
    fun `pem이 아닌 파일은 무시`() {
        TestSigningKeys.write(dir, CURRENT_KID, TestSigningKeys.CURRENT_PEM)
        dir.resolve("README.txt").writeText("메모")

        val keys = loader.load(SigningKeyProperties(dir, activeKid = CURRENT_KID))

        assertEquals(1, keys.published.size)
    }

    @Test
    fun `폴더가 없으면 기동 실패`() {
        assertFailsWith<IllegalStateException> {
            loader.load(SigningKeyProperties(dir.resolve("missing"), activeKid = CURRENT_KID))
        }
    }

    @Test
    fun `활성 키 파일이 없으면 기동 실패`() {
        TestSigningKeys.write(dir, CURRENT_KID, TestSigningKeys.CURRENT_PEM)

        assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir, activeKid = NEXT_KID)) }
    }

    @Test
    fun `자동 생성이 아니면 활성 키를 지정해야 함`() {
        TestSigningKeys.write(dir, CURRENT_KID, TestSigningKeys.CURRENT_PEM)

        assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir)) }
    }

    @Test
    fun `서명 키가 최소 크기보다 작으면 기동 실패`() {
        TestSigningKeys.write(dir, CURRENT_KID, TestSigningKeys.weakPem())

        val error = assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir, activeKid = CURRENT_KID)) }
        assertTrue(error.message!!.contains(AuthPolicy.SIGNING_KEY_SIZE.toString()))
    }

    @Test
    fun `kid 형식이 dozy-연도-월이 아니면 기동 실패`() {
        TestSigningKeys.write(dir, "2026-09", TestSigningKeys.CURRENT_PEM)

        assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir, activeKid = "2026-09")) }
    }

    @Test
    fun `PKCS8 PEM이 아니면 기동 실패`() {
        TestSigningKeys.write(dir, CURRENT_KID, "not a key")

        assertFailsWith<IllegalStateException> { loader.load(SigningKeyProperties(dir, activeKid = CURRENT_KID)) }
    }

    @Test
    fun `자동 생성은 빈 폴더에 현재 연월 kid로 만들고 다음 기동부터 재사용`() {
        val keysDir = dir.resolve("signing-keys")
        val properties = SigningKeyProperties(keysDir, autoGenerate = true)

        val first = loader.load(properties)
        // kid 형식(dozy-{연도}-{월})은 명세 값이라 FIXED_CLOCK(2026-09-25)에서 기대하는 이름을 그대로 씁니다
        val saved = keysDir.resolve("dozy-2026-09.pem").readText()
        val later = SigningKeyLoader(Clock.offset(FIXED_CLOCK, java.time.Duration.ofDays(100)).withZone(ZoneOffset.UTC))
        val second = later.load(properties)

        assertEquals("dozy-2026-09", first.active.keyID)
        assertEquals(first.active.toPublicJWK(), second.active.toPublicJWK())
        assertEquals(saved, keysDir.resolve("dozy-2026-09.pem").readText())
    }

    @Test
    fun `자동 생성은 지정한 kid의 키가 없을 때만 만듦`() {
        TestSigningKeys.write(dir, CURRENT_KID, TestSigningKeys.CURRENT_PEM)

        val keys = loader.load(SigningKeyProperties(dir, activeKid = NEXT_KID, autoGenerate = true))

        assertEquals(NEXT_KID, keys.active.keyID)
        assertEquals(listOf(CURRENT_KID, NEXT_KID), keys.published.map { it.keyID })
    }

    @Test
    fun `자동 생성한 키 파일은 소유자만 읽고 쓸 수 있는 PKCS8 PEM`() {
        loader.load(SigningKeyProperties(dir, autoGenerate = true))

        val file = dir.resolve("dozy-2026-09.pem")
        if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
        assertTrue(file.readText().startsWith("-----BEGIN PRIVATE KEY-----"))
    }
}
