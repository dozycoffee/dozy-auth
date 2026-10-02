package com.dozycoffee.auth.server.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** SEC-02 응답의 이메일·전화번호 마스킹. 기대값은 domain.md SEC-02의 예시 형식입니다. */
class MaskingTest {
    @Test
    fun `SEC-02 이메일은 로컬 부분의 앞 두 글자와 도메인만 남김`() {
        assertEquals("ki***@dozycoffee.com", Masking.email(Email("kim.doyun@dozycoffee.com")))
    }

    @Test
    fun `SEC-02 이메일의 대소문자는 입력 그대로 남김`() {
        assertEquals("Ki***@DozyCoffee.com", Masking.email(Email("Kim@DozyCoffee.com")))
    }

    @ParameterizedTest
    @CsvSource(
        "kim@dozycoffee.com, ki***@dozycoffee.com",
        "ab@dozycoffee.com, a***@dozycoffee.com",
        "a@dozycoffee.com, ***@dozycoffee.com",
    )
    fun `SEC-02 로컬 부분이 짧아도 전체를 드러내지 않음`(
        email: String,
        expected: String,
    ) {
        assertEquals(expected, Masking.email(Email(email)))
    }

    @Test
    fun `SEC-02 가린 부분은 길이와 관계없이 같아 원래 길이를 드러내지 않음`() {
        assertEquals(Masking.email(Email("abc@dozycoffee.com")), Masking.email(Email("abcdefghij@dozycoffee.com")))
    }

    @Test
    fun `SEC-02 이메일의 로컬 부분은 코드 포인트 단위로 자름`() {
        assertEquals("😀😀***@dozycoffee.com", Masking.email(Email("😀😀😀@dozycoffee.com")))
    }

    @ParameterizedTest
    @CsvSource(
        "010-1234-5678, 010-****-5678",
        "01012345678, 010****5678",
        "010 1234 5678, 010 **** 5678",
        "+82-10-1234-5678, +82-1*-****-5678",
    )
    fun `SEC-02 전화번호는 숫자의 앞 세 자리와 뒤 네 자리만 남기고 구분 문자는 그대로 둠`(
        phone: String,
        expected: String,
    ) {
        assertEquals(expected, Masking.phone(phone))
    }

    @ParameterizedTest
    @CsvSource(
        "(02) 1234-5678, (**) ****-5678",
        "02-123-4567, **-***-4567",
        "12345678, ****5678",
    )
    fun `SEC-02 숫자가 짧은 전화번호는 네 자리 이상을 가리도록 뒤 네 자리만 남김`(
        phone: String,
        expected: String,
    ) {
        assertEquals(expected, Masking.phone(phone))
    }

    @ParameterizedTest
    @CsvSource(
        "123-4567, ***-****",
        "1234567, *******",
    )
    fun `SEC-02 숫자가 너무 짧은 전화번호는 모두 가림`(
        phone: String,
        expected: String,
    ) {
        assertEquals(expected, Masking.phone(phone))
    }

    @Test
    fun `SEC-02 전화번호의 전각 숫자도 숫자로 가림`() {
        assertEquals("０１０-****-５６７８", Masking.phone("０１０-１２３４-５６７８"))
    }

    @Test
    fun `SEC-02 숫자가 없는 전화번호는 그대로`() {
        assertEquals("-", Masking.phone("-"))
    }
}
