package com.dozycoffee.auth.server.domain.account

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.domain.account.AccountStatus.ACTIVE
import com.dozycoffee.auth.server.domain.account.AccountStatus.DEACTIVATED
import com.dozycoffee.auth.server.domain.account.AccountStatus.PENDING
import com.dozycoffee.auth.server.domain.account.AccountStatus.SUSPENDED
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 계정 상태 전이 (domain.md §3 ACC-01). 허용 전이는 명세의 상태 그림을 그대로 옮겨 적고, 에러 code·status는
 * api/conventions.md §11의 값을 그대로 씁니다.
 */
class AccountStatusTransitionTest {
    @Test
    fun `ACC-01 상태 그림에 있는 전이만 허용`() {
        val allowed =
            setOf(
                PENDING to ACTIVE,
                PENDING to DEACTIVATED,
                ACTIVE to SUSPENDED,
                SUSPENDED to ACTIVE,
                ACTIVE to DEACTIVATED,
                SUSPENDED to DEACTIVATED,
            )

        AccountStatus.entries.forEach { from ->
            AccountStatus.entries.forEach { to ->
                assertEquals(from to to in allowed, from.canTransitionTo(to), "$from → $to")
            }
        }
    }

    @Test
    fun `ACC-01 초대 수락으로 PENDING 계정을 ACTIVE로 바꿈`() {
        assertEquals(ACTIVE, account(PENDING).activate().status)
    }

    @ParameterizedTest
    @EnumSource(names = ["ACTIVE", "SUSPENDED", "DEACTIVATED"])
    fun `ACC-01 PENDING이 아닌 계정을 초대 수락으로 활성화하면 INVALID_STATE`(status: AccountStatus) {
        assertInvalidState { account(status).activate() }
    }

    @Test
    fun `ACC-01 정지 해제로 SUSPENDED 계정을 ACTIVE로 바꿈`() {
        assertEquals(ACTIVE, account(SUSPENDED).reactivate().status)
    }

    @ParameterizedTest
    @EnumSource(names = ["PENDING", "ACTIVE", "DEACTIVATED"])
    fun `ACC-01 SUSPENDED가 아닌 계정을 정지 해제하면 INVALID_STATE`(status: AccountStatus) {
        assertInvalidState { account(status).reactivate() }
    }

    @Test
    fun `ACC-01 ACTIVE 계정을 정지함`() {
        assertEquals(SUSPENDED, account(ACTIVE).suspend().status)
    }

    @ParameterizedTest
    @EnumSource(names = ["PENDING", "SUSPENDED", "DEACTIVATED"])
    fun `ACC-01 ACTIVE가 아닌 계정을 정지하면 INVALID_STATE`(status: AccountStatus) {
        assertInvalidState { account(status).suspend() }
    }

    @ParameterizedTest
    @EnumSource(names = ["PENDING", "ACTIVE", "SUSPENDED"])
    fun `ACC-01 비활성화하면 DEACTIVATED가 되고 전환 시각을 남김`(status: AccountStatus) {
        val deactivated = account(status).deactivate(NOW)

        assertEquals(DEACTIVATED, deactivated.status)
        assertEquals(NOW, deactivated.deactivatedAt)
    }

    @Test
    fun `ACC-04 비활성화한 계정은 다시 비활성화할 수 없음`() {
        assertInvalidState { account(DEACTIVATED).deactivate(NOW) }
    }

    @Test
    fun `ACC-06 초대를 취소하면 PENDING 계정이 DEACTIVATED가 됨`() {
        val cancelled = account(PENDING).cancelInvitation(NOW)

        assertEquals(DEACTIVATED, cancelled.status)
        assertEquals(NOW, cancelled.deactivatedAt)
    }

    @ParameterizedTest
    @EnumSource(names = ["ACTIVE", "SUSPENDED", "DEACTIVATED"])
    fun `ACC-06 PENDING이 아닌 계정의 초대 재발송·취소는 INVALID_STATE`(status: AccountStatus) {
        assertInvalidState { account(status).ensureInvitationPending() }
        assertInvalidState { account(status).cancelInvitation(NOW) }
    }

    @ParameterizedTest
    @EnumSource(names = ["PENDING", "ACTIVE", "SUSPENDED"])
    fun `DEACTIVATED가 아니면 정보를 수정할 수 있음`(status: AccountStatus) {
        account(status).ensureProfileEditable()
    }

    @Test
    fun `DEACTIVATED 계정의 정보 수정은 INVALID_STATE`() {
        assertInvalidState { account(DEACTIVATED).ensureProfileEditable() }
    }

    @Test
    fun `ACTIVE인 사람 계정에는 비밀번호 재설정 메일을 보낼 수 있음`() {
        account(ACTIVE).ensurePasswordResettable()
        account(ACTIVE).copy(type = PrincipalType.PARTNER).ensurePasswordResettable()
    }

    @ParameterizedTest
    @EnumSource(names = ["PENDING", "SUSPENDED", "DEACTIVATED"])
    fun `ACTIVE가 아닌 계정의 비밀번호 재설정 메일은 INVALID_STATE`(status: AccountStatus) {
        assertInvalidState { account(status).ensurePasswordResettable() }
    }

    @Test
    fun `system client의 비밀번호 재설정 메일은 INVALID_STATE`() {
        assertInvalidState { account(ACTIVE).copy(type = PrincipalType.SYSTEM).ensurePasswordResettable() }
    }

    @Test
    fun `전이에 실패하면 계정은 바뀌지 않음`() {
        val account = account(PENDING)

        assertInvalidState { account.suspend() }

        assertEquals(PENDING, account.status)
        assertNull(account.deactivatedAt)
    }

    private fun assertInvalidState(block: () -> Unit) {
        val error = assertFailsWith<InvalidAccountStateException>(block = block)
        assertEquals("INVALID_STATE", error.code)
        assertEquals(409, error.status)
    }

    private fun account(status: AccountStatus) =
        Account(
            id = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f"),
            type = PrincipalType.EMPLOYEE,
            status = status,
            failedLoginCount = 0,
            lockedUntil = null,
            deactivatedAt = null,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
