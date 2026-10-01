package com.dozycoffee.auth.server.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * 1회용 비밀값 원문: refresh token, verification 토큰, client secret (SES-02, VER-02, CLI-02).
 *
 * `policy.secret-bytes` 난수를 패딩 없는 base64url로 인코딩합니다. 원문은 응답이나 메일로 한 번만 내보내고
 * 저장하지 않으며, 저장과 조회에는 [hash]를 씁니다 (SEC-01). 로그에 남지 않도록 [toString]은 원문을 가립니다 (SEC-03).
 */
@JvmInline
value class OpaqueSecret private constructor(
    val value: String,
) {
    fun hash(): SecretHash = SecretHash.of(value)

    override fun toString(): String = "OpaqueSecret(***)"

    companion object {
        private val DEFAULT_RANDOM = SecureRandom()

        /** 새 원문을 만듭니다. 난수는 [SecureRandom]만 씁니다 (SEC-05). */
        fun generate(random: SecureRandom = DEFAULT_RANDOM): OpaqueSecret {
            val bytes = ByteArray(AuthPolicy.SECRET_BYTES).also(random::nextBytes)
            return OpaqueSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}

/**
 * [OpaqueSecret]의 SHA-256 hex (data-model.md §1, `char(64)`). 난수 원문이라 느린 해시가 필요 없습니다 (CLI-02).
 *
 * 저장된 해시와 비교할 때는 `==` 대신 상수 시간 비교인 [matches]를 씁니다 (SEC-05).
 */
@JvmInline
value class SecretHash(
    val hex: String,
) {
    init {
        require(HEX.matches(hex)) { "SHA-256 hex 형식이 아닙니다" }
    }

    /** 상수 시간으로 비교합니다 (SEC-05). */
    fun matches(other: SecretHash): Boolean = MessageDigest.isEqual(hex.toByteArray(), other.hex.toByteArray())

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")

        /**
         * 원문의 해시를 만듭니다. 요청으로 받은 값처럼 [OpaqueSecret]으로 만들지 않은 원문에도 씁니다.
         * 형식이 틀린 값도 해시하며, 일치하는 저장값이 없으므로 없는 토큰과 같게 처리됩니다.
         */
        fun of(raw: String): SecretHash {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return SecretHash(HexFormat.of().formatHex(digest))
        }
    }
}
