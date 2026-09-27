package com.dozycoffee.auth.server.adapter.out.jwt

import com.dozycoffee.auth.core.AccessTokenFormat
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * 서명 키 폴더를 읽습니다 (configuration.md §2, §3, token.md §7).
 *
 * - 폴더 안의 `{kid}.pem`(PKCS#8 RSA 개인키)을 모두 읽고, 모든 공개키를 게시합니다.
 * - 서명은 `activeKid` 키 하나로만 합니다.
 * - `autoGenerate`면 키가 없을 때 만들어 저장하고, 다음 기동부터 재사용합니다.
 * - 문제가 있으면 [IllegalStateException]으로 기동을 멈춥니다.
 */
class SigningKeyLoader(
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    fun load(properties: SigningKeyProperties): SigningKeys {
        val dir = properties.keysDir
        if (properties.autoGenerate) ensureKeyExists(dir, properties.activeKid)

        check(dir.isDirectory()) { "서명 키 폴더가 없습니다: $dir" }
        val files = dir.listDirectoryEntries("*$EXTENSION").sortedBy { it.fileName.toString() }
        check(files.isNotEmpty()) { "서명 키 폴더에 $EXTENSION 파일이 없습니다: $dir" }

        val keys = files.map(::readKey)
        val activeKid =
            properties.activeKid
                ?: if (properties.autoGenerate) keys.last().keyID else error("서명에 쓸 키(active-kid)를 지정해야 합니다")
        val active = keys.firstOrNull { it.keyID == activeKid } ?: error("활성 서명 키 파일이 없습니다: $activeKid$EXTENSION")

        return SigningKeys(active = active, published = keys.map(RSAKey::toPublicJWK))
    }

    private fun readKey(file: Path): RSAKey {
        val kid = file.nameWithoutExtension
        check(KID_PATTERN.matches(kid)) { "서명 키 파일 이름은 dozy-{연도}-{월} 형식이어야 합니다: ${file.fileName}" }

        val (privateKey, publicKey) =
            runCatching { SigningKeyPem.read(file.readText()) }
                .getOrElse { throw IllegalStateException("서명 키를 읽을 수 없습니다: ${file.fileName} (${it.message})") }
        check(publicKey.modulus.bitLength() >= MIN_KEY_SIZE) {
            "서명 키는 RSA $MIN_KEY_SIZE 비트 이상이어야 합니다: ${file.fileName} (${publicKey.modulus.bitLength()}비트)"
        }

        return RSAKey
            .Builder(publicKey)
            .privateKey(privateKey)
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.parse(AccessTokenFormat.ALGORITHM))
            .build()
    }

    /** 자동 생성: 지정한 kid가 없거나, kid를 안 줬는데 폴더가 비어 있으면 키를 만든다. */
    private fun ensureKeyExists(
        dir: Path,
        activeKid: String?,
    ) {
        Files.createDirectories(dir)
        val kid =
            when {
                activeKid != null -> activeKid.takeUnless { dir.resolve("$it$EXTENSION").exists() }
                dir.listDirectoryEntries("*$EXTENSION").isEmpty() -> KID_FORMAT.format(clock.instant().atZone(ZoneOffset.UTC))
                else -> null
            } ?: return

        val file = dir.resolve("$kid$EXTENSION")
        Files.writeString(file, SigningKeyPem.generate(MIN_KEY_SIZE, random), CREATE_NEW, WRITE)
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    companion object {
        /** `policy.signing-key-size` (domain.md §2). */
        const val MIN_KEY_SIZE: Int = 3072

        private const val EXTENSION = ".pem"

        /** `dozy-{연도}-{월}` (token.md §2). */
        private val KID_PATTERN = Regex("dozy-\\d{4}-\\d{2}")
        private val KID_FORMAT = DateTimeFormatter.ofPattern("'dozy-'yyyy-MM")
    }
}
