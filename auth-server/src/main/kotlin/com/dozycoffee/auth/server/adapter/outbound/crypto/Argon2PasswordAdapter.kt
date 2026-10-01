package com.dozycoffee.auth.server.adapter.outbound.crypto

import com.dozycoffee.auth.server.application.port.outbound.crypto.HashPasswordPort
import com.dozycoffee.auth.server.application.port.outbound.crypto.VerifyPasswordPort
import com.dozycoffee.auth.server.domain.credential.PasswordHash
import com.dozycoffee.auth.server.domain.credential.RawPassword
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * argon2id 비밀번호 해시 (PWD-04, ADR-0027). Spring Security [Argon2PasswordEncoder](Bouncy Castle)를 씁니다.
 *
 * - 새 해시는 [Argon2Properties]의 파라미터로 만들고, salt는 encoder가 `SecureRandom`으로 만듭니다.
 * - 검증은 저장된 인코딩 문자열의 파라미터로 하고, 해시 비교는 encoder가 상수 시간으로 합니다 (SEC-05).
 *   형식이 틀린 해시는 예외 없이 `false`입니다.
 * - 해시가 없으면 기동 때 만든 가짜 해시로 검증해 응답 시간을 맞춥니다 (LGN-02). 가짜 해시의 원문은 버린 난수라
 *   어떤 입력과도 맞지 않지만, 결과와 관계없이 `false`를 돌려줍니다.
 */
@Component
class Argon2PasswordAdapter(
    properties: Argon2Properties,
) : HashPasswordPort,
    VerifyPasswordPort {
    private val encoder =
        with(properties) { Argon2PasswordEncoder(saltLength, hashLength, parallelism, memoryKib, iterations) }

    private val dummyHash: String = encode(randomSecret())

    override fun hash(password: RawPassword): PasswordHash = PasswordHash(encode(password.value))

    override fun verify(
        password: RawPassword,
        hash: PasswordHash?,
    ): Boolean {
        if (hash == null) {
            encoder.matches(password.value, dummyHash)
            return false
        }
        return encoder.matches(password.value, hash.encoded)
    }

    private fun encode(raw: String): String = checkNotNull(encoder.encode(raw)) { "argon2 해시를 만들지 못했습니다" }

    private fun randomSecret(): String {
        val bytes = ByteArray(DUMMY_SECRET_BYTES).also(SecureRandom()::nextBytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private companion object {
        const val DUMMY_SECRET_BYTES = 32
    }
}
