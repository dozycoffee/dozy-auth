package com.dozycoffee.auth.server.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** data-model.md §1(이메일): 입력값 그대로 저장하고 유일성은 소문자로 비교합니다. */
class EmailTest {
    @Test
    fun `입력값은 대소문자를 그대로 보관`() {
        assertEquals("Kim.Barista@DozyCoffee.com", Email("Kim.Barista@DozyCoffee.com").value)
    }

    @Test
    fun `대소문자만 다른 이메일은 조회 키가 같음`() {
        assertEquals(Email("kim@dozycoffee.com").lookupKey, Email("Kim@DozyCoffee.COM").lookupKey)
        assertEquals("kim@dozycoffee.com", Email("Kim@DozyCoffee.COM").lookupKey)
    }

    @Test
    fun `저장할 수 있는 최대 길이까지 받음`() {
        val local = "a".repeat(Email.MAX_LENGTH - "@dozycoffee.com".length)

        Email("$local@dozycoffee.com")
    }

    @Test
    fun `저장할 수 있는 최대 길이를 넘으면 거부`() {
        val local = "a".repeat(Email.MAX_LENGTH - "@dozycoffee.com".length + 1)

        assertFailsWith<IllegalArgumentException> { Email("$local@dozycoffee.com") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "kim", "kim@", "@dozycoffee.com", "kim@@dozycoffee.com", "kim @dozycoffee.com"])
    fun `로컬 부분과 도메인이 @ 하나로 나뉘지 않으면 거부`(value: String) {
        assertFailsWith<IllegalArgumentException> { Email(value) }
    }

    @Test
    fun `SEC-03 거부 메시지에 입력한 주소를 넣지 않음`() {
        val error = assertFailsWith<IllegalArgumentException> { Email("kim@@dozycoffee.com") }

        assertEquals(false, error.message.orEmpty().contains("kim"))
    }
}
