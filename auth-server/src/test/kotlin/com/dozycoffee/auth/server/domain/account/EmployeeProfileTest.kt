package com.dozycoffee.auth.server.domain.account

import com.dozycoffee.auth.server.domain.Email
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 직원 profile (data-model.md §3.2)과 비활성화 때의 개인정보 파기 (domain.md ACC-04). 파기 값은 명세의 문자열 그대로 씁니다. */
class EmployeeProfileTest {
    private val principalId = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")

    @Test
    fun `ACC-04 개인정보를 파기하면 이메일과 이름을 정해진 값으로 바꾸고 전화번호와 주소를 지움`() {
        val profile = EmployeeProfile(principalId, Email("Kim.Barista@DozyCoffee.com"), "김바리", "010-1234-5678", "서울시 성동구")

        val scrubbed = profile.scrubbed()

        assertEquals("deleted+0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f@invalid.local", scrubbed.email.value)
        assertEquals("탈퇴 사용자", scrubbed.name)
        assertNull(scrubbed.phone)
        assertNull(scrubbed.address)
        assertEquals(principalId, scrubbed.principalId)
    }

    @Test
    fun `저장할 수 있는 길이를 넘는 이름은 거부`() {
        assertFailsWith<IllegalArgumentException> {
            EmployeeProfile(principalId, Email("kim@dozycoffee.com"), "가".repeat(EmployeeProfile.NAME_MAX_LENGTH + 1), null, null)
        }
    }

    @Test
    fun `저장할 수 있는 길이를 넘는 전화번호와 주소는 거부`() {
        val email = Email("kim@dozycoffee.com")

        assertFailsWith<IllegalArgumentException> {
            EmployeeProfile(principalId, email, "김", "0".repeat(EmployeeProfile.PHONE_MAX_LENGTH + 1), null)
        }
        assertFailsWith<IllegalArgumentException> {
            EmployeeProfile(principalId, email, "김", null, "가".repeat(EmployeeProfile.ADDRESS_MAX_LENGTH + 1))
        }
    }
}
