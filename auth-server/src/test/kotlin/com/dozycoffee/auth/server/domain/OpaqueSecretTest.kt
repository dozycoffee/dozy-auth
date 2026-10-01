package com.dozycoffee.auth.server.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 1회용 비밀값(refresh token, verification 토큰, client secret)의 생성과 해시. SES-02, VER-02, CLI-02, SEC-01, SEC-05. */
class OpaqueSecretTest {
    @Test
    fun `원문은 정해진 바이트 수의 난수를 패딩 없는 base64url로 인코딩`() {
        val value = OpaqueSecret.generate().value

        assertTrue(Regex("[A-Za-z0-9_-]+").matches(value))
        assertEquals(AuthPolicy.SECRET_BYTES, Base64.getUrlDecoder().decode(value).size)
    }

    @Test
    fun `만들 때마다 다른 원문`() {
        assertNotEquals(OpaqueSecret.generate().value, OpaqueSecret.generate().value)
    }

    @Test
    fun `SEC-03 문자열로 바꿔도 원문이 드러나지 않음`() {
        val secret = OpaqueSecret.generate()

        assertFalse(secret.toString().contains(secret.value))
        assertFalse("$secret".contains(secret.value))
    }

    @Test
    fun `SEC-01 해시는 원문의 SHA-256 hex`() {
        // SHA-256("abc"), FIPS 180-2 예시 값
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SecretHash.of("abc").hex)
    }

    @Test
    fun `만든 원문의 해시는 같은 원문을 다시 해시한 값과 일치`() {
        val secret = OpaqueSecret.generate()

        assertTrue(secret.hash().matches(SecretHash.of(secret.value)))
    }

    @Test
    fun `SEC-05 원문이 다르면 해시가 일치하지 않음`() {
        assertFalse(SecretHash.of("abc").matches(SecretHash.of("abd")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015a",
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            "za7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ],
    )
    fun `소문자 hex 64자리가 아니면 해시로 받지 않음`(value: String) {
        assertFailsWith<IllegalArgumentException> { SecretHash(value) }
    }
}
