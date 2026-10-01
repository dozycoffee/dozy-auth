package com.dozycoffee.auth.server.domain.credential

import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * 비밀번호 규칙 (domain.md §4). 길이 경계는 `AuthPolicy` 값에서 만들고, 에러 code·status는 api/conventions.md §11의 값을 그대로 씁니다.
 *
 * 실패 메시지에 비밀번호가 찍히지 않도록 비밀번호를 `assertEquals`의 인자로 넘기지 않습니다 (SEC-03).
 */
class PasswordPolicyTest {
    private val email = Email("Kim.Barista@DozyCoffee.com")

    @Test
    fun `PWD-01 최소 길이면 허용`() {
        PasswordPolicy.check(password(AuthPolicy.PASSWORD_MIN_LENGTH), email)
    }

    @Test
    fun `PWD-01 최소 길이보다 짧으면 VALIDATION_FAILED`() {
        val error =
            assertFailsWith<PasswordPolicyViolationException> { PasswordPolicy.check(password(AuthPolicy.PASSWORD_MIN_LENGTH - 1), email) }

        assertEquals("VALIDATION_FAILED", error.code)
        assertEquals(400, error.status)
    }

    @Test
    fun `PWD-01 비어 있으면 거부`() {
        assertFailsWith<PasswordPolicyViolationException> { PasswordPolicy.check(RawPassword(""), email) }
    }

    @Test
    fun `PWD-01 최대 길이면 허용`() {
        PasswordPolicy.check(password(AuthPolicy.PASSWORD_MAX_LENGTH), email)
    }

    @Test
    fun `PWD-01 최대 길이보다 길면 VALIDATION_FAILED`() {
        val error =
            assertFailsWith<PasswordPolicyViolationException> { PasswordPolicy.check(password(AuthPolicy.PASSWORD_MAX_LENGTH + 1), email) }

        assertEquals("VALIDATION_FAILED", error.code)
        assertEquals(400, error.status)
    }

    @Test
    fun `PWD-01 UTF-16 두 단위로 표현되는 문자도 한 글자로 셈`() {
        val emoji = "😀" // U+1F600, UTF-16 두 단위

        PasswordPolicy.check(RawPassword(emoji.repeat(AuthPolicy.PASSWORD_MAX_LENGTH)), email)
        assertFailsWith<PasswordPolicyViolationException> {
            PasswordPolicy.check(RawPassword(emoji.repeat(AuthPolicy.PASSWORD_MIN_LENGTH - 1)), email)
        }
    }

    @Test
    fun `PWD-01 한글도 한 글자로 셈`() {
        PasswordPolicy.check(RawPassword("가".repeat(AuthPolicy.PASSWORD_MIN_LENGTH)), email)
        assertFailsWith<PasswordPolicyViolationException> {
            PasswordPolicy.check(RawPassword("가".repeat(AuthPolicy.PASSWORD_MAX_LENGTH + 1)), email)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["abcdefghij", "1234567890", "ABCDEFGHIJ", "          "])
    fun `PWD-02 문자 종류가 하나뿐이어도 허용`(value: String) {
        PasswordPolicy.check(RawPassword(value), email)
    }

    @Test
    fun `PWD-03 이메일과 같으면 VALIDATION_FAILED`() {
        val error = assertFailsWith<PasswordPolicyViolationException> { PasswordPolicy.check(RawPassword(email.value), email) }

        assertEquals("VALIDATION_FAILED", error.code)
        assertEquals(400, error.status)
    }

    @ParameterizedTest
    @ValueSource(strings = ["kim.barista@dozycoffee.com", "KIM.BARISTA@DOZYCOFFEE.COM"])
    fun `PWD-03 대소문자만 다른 이메일이어도 거부`(value: String) {
        assertFailsWith<PasswordPolicyViolationException> { PasswordPolicy.check(RawPassword(value), email) }
    }

    @Test
    fun `PWD-03 이메일을 포함하기만 하면 허용`() {
        PasswordPolicy.check(RawPassword("${email.value}!"), email)
    }

    @Test
    fun `SEC-03 거부 메시지에 비밀번호와 이메일을 넣지 않음`() {
        val tooShort = assertFailsWith<PasswordPolicyViolationException> { PasswordPolicy.check(RawPassword("secret"), email) }
        val sameAsEmail = assertFailsWith<PasswordPolicyViolationException> { PasswordPolicy.check(RawPassword(email.value), email) }

        assertFalse(tooShort.message.orEmpty().contains("secret"))
        assertFalse(sameAsEmail.message.orEmpty().contains("Kim", ignoreCase = true))
    }

    private fun password(length: Int) = RawPassword("a".repeat(length))
}
