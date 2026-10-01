package com.dozycoffee.auth.server.domain.credential

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** 비밀번호 원문과 해시 값 타입. 로그에 값이 남지 않아야 합니다 (SEC-03). */
class PasswordValuesTest {
    @Test
    fun `SEC-03 비밀번호 원문은 문자열로 바꿔도 값을 가림`() {
        assertFalse(RawPassword("correct horse battery staple").toString().contains("horse"))
        assertFalse("${RawPassword("correct horse battery staple")}".contains("horse"))
    }

    @Test
    fun `해시는 문자열로 바꿔도 값을 가림`() {
        assertFalse(PasswordHash("\$argon2id\$v=19\$m=16,t=2,p=1\$c2FsdA\$aGFzaA").toString().contains("argon2id"))
    }

    @Test
    fun `해시가 비어 있으면 거부`() {
        assertFailsWith<IllegalArgumentException> { PasswordHash(" ") }
    }

    @Test
    fun `해시가 저장할 수 있는 길이를 넘으면 거부`() {
        PasswordHash("a".repeat(PasswordHash.MAX_LENGTH))
        assertFailsWith<IllegalArgumentException> { PasswordHash("a".repeat(PasswordHash.MAX_LENGTH + 1)) }
    }
}
