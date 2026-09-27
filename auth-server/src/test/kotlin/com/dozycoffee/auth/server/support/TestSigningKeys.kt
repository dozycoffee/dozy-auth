package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.server.adapter.outbound.jwt.SigningKeyPem
import com.dozycoffee.auth.server.domain.AuthPolicy
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.writeText

/**
 * 테스트용 서명 키. 3072비트 키 생성이 느려서 테스트 JVM 전체에서 한 번씩만 만듭니다.
 */
object TestSigningKeys {
    /** 서명에 쓰는 키. */
    const val CURRENT_KID = "dozy-2026-09"

    /** 교체 준비 중인 키 (JWKS에만 게시). */
    const val NEXT_KID = "dozy-2027-09"

    val CURRENT_PEM: String by lazy { generate() }
    val NEXT_PEM: String by lazy { generate() }

    /** 폴더에 `{kid}.pem`을 씁니다. */
    fun write(
        dir: Path,
        kid: String,
        pem: String,
    ): Path = dir.resolve("$kid.pem").also { it.writeText(pem) }

    /** 폴더에 현재 키와 다음 키를 씁니다. */
    fun writeCurrentAndNext(dir: Path): Path {
        write(dir, CURRENT_KID, CURRENT_PEM)
        write(dir, NEXT_KID, NEXT_PEM)
        return dir
    }

    /** 명세보다 작은 키 (거부되는지 확인용). */
    fun weakPem(): String = SigningKeyPem.generate(2048, SecureRandom())

    private fun generate(): String = SigningKeyPem.generate(AuthPolicy.SIGNING_KEY_SIZE, SecureRandom())
}
