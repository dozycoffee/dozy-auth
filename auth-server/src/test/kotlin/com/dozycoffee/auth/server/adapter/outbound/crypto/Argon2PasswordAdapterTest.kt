package com.dozycoffee.auth.server.adapter.outbound.crypto

import com.dozycoffee.auth.server.domain.credential.PasswordHash
import com.dozycoffee.auth.server.domain.credential.RawPassword
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * argon2id 해시와 검증 (PWD-04, LGN-02, ADR-0027).
 *
 * 테스트 속도를 위해 기본값보다 가벼운 파라미터를 씁니다. 인코딩 형식(`$argon2id$v=19$m=..,t=..,p=..$`)은 argon2 PHC 문자열 형식 그대로 기대값으로 씁니다.
 * 실패 메시지에 비밀번호가 찍히지 않도록 `assertTrue`·`assertFalse`만 씁니다 (SEC-03).
 */
class Argon2PasswordAdapterTest {
    private val properties = Argon2Properties(memoryKib = 1024, iterations = 1, parallelism = 1)
    private val adapter = Argon2PasswordAdapter(properties)
    private val password = RawPassword("correct horse battery staple")

    @Test
    fun `PWD-04 해시는 설정한 파라미터를 담은 argon2id 인코딩 문자열`() {
        val encoded = adapter.hash(password).encoded

        with(properties) { assertTrue(encoded.startsWith("\$argon2id\$v=19\$m=$memoryKib,t=$iterations,p=$parallelism\$")) }
    }

    @Test
    fun `lane당 최소 메모리보다 작은 설정은 거부`() {
        assertFailsWith<IllegalArgumentException> { Argon2Properties(memoryKib = 15, parallelism = 2) }
    }

    @Test
    fun `16바이트보다 짧은 salt 설정은 거부`() {
        assertFailsWith<IllegalArgumentException> { Argon2Properties(saltLength = 8) }
    }

    @Test
    fun `같은 비밀번호도 salt가 달라 해시가 매번 다름`() {
        assertNotEquals(adapter.hash(password), adapter.hash(password))
    }

    @Test
    fun `해시한 비밀번호와 같으면 검증 성공`() {
        assertTrue(adapter.verify(password, adapter.hash(password)))
    }

    @Test
    fun `해시한 비밀번호와 다르면 검증 실패`() {
        assertFalse(adapter.verify(RawPassword("correct horse battery stapler"), adapter.hash(password)))
    }

    @Test
    fun `PWD-04 다른 파라미터로 만든 기존 해시도 검증`() {
        val legacy = Argon2PasswordEncoder(16, 32, 2, 2048, 2).encode(password.value)!!

        assertTrue(adapter.verify(password, PasswordHash(legacy)))
        assertFalse(adapter.verify(RawPassword("wrong password"), PasswordHash(legacy)))
    }

    @Test
    fun `LGN-02 해시가 없으면 가짜 해시로 검증하고 실패`() {
        assertFalse(adapter.verify(password, null))
    }

    @Test
    fun `형식이 틀린 해시는 예외 없이 검증 실패`() {
        assertFalse(adapter.verify(password, PasswordHash("not-an-argon2-hash")))
    }
}
