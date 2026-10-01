package com.dozycoffee.auth.server.domain.account

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 로그인 실패 잠금 (domain.md ACC-02, LGN-01). 기준 횟수와 잠금 시간은 `AuthPolicy`에서 가져옵니다. */
class LoginLockTest {
    @Test
    fun `LGN-01 기준 횟수에 못 미친 실패는 횟수만 늘리고 잠그지 않음`() {
        val result = account(failedLoginCount = AuthPolicy.LOGIN_LOCK_THRESHOLD - 2).recordLoginFailure(NOW)

        assertFalse(result.locked)
        assertEquals(AuthPolicy.LOGIN_LOCK_THRESHOLD - 1, result.account.failedLoginCount)
        assertNull(result.account.lockedUntil)
    }

    @Test
    fun `LGN-01 기준 횟수에 도달하면 잠금 시간만큼 잠그고 실패 횟수를 되돌림`() {
        val result = account(failedLoginCount = AuthPolicy.LOGIN_LOCK_THRESHOLD - 1).recordLoginFailure(NOW)

        assertTrue(result.locked)
        assertEquals(NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION), result.account.lockedUntil)
        assertEquals(0, result.account.failedLoginCount)
    }

    @Test
    fun `LGN-01 처음부터 기준 횟수만큼 연속으로 실패하면 마지막 실패에서 잠김`() {
        var account = account()
        val locks =
            (1..AuthPolicy.LOGIN_LOCK_THRESHOLD).map {
                val result = account.recordLoginFailure(NOW)
                account = result.account
                result.locked
            }

        assertEquals(List(AuthPolicy.LOGIN_LOCK_THRESHOLD - 1) { false } + true, locks)
    }

    @Test
    fun `ACC-02 잠금 시각 직전까지는 잠겨 있음`() {
        val account = lockedAccount()

        assertTrue(account.isLocked(NOW))
        assertTrue(account.isLocked(NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION).minusNanos(1)))
    }

    @Test
    fun `ACC-02 잠금 시각이 되면 자동으로 풀림`() {
        val account = lockedAccount()

        assertFalse(account.isLocked(NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION)))
    }

    @Test
    fun `ACC-02 잠근 적이 없으면 잠겨 있지 않음`() {
        assertFalse(account().isLocked(NOW))
    }

    @Test
    fun `LGN-01 잠금이 풀린 뒤에는 다시 기준 횟수만큼 실패해야 잠김`() {
        var account = lockedAccount()
        val afterLock = NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION)

        repeat(AuthPolicy.LOGIN_LOCK_THRESHOLD - 1) {
            val result = account.recordLoginFailure(afterLock)
            assertFalse(result.locked)
            account = result.account
        }
        val last = account.recordLoginFailure(afterLock)

        assertTrue(last.locked)
        assertEquals(afterLock.plus(AuthPolicy.LOGIN_LOCK_DURATION), last.account.lockedUntil)
    }

    @Test
    fun `LGN-01 잠겨 있으면 남은 시간을 담아 TOO_MANY_ATTEMPTS`() {
        val oneMinuteLater = NOW.plus(Duration.ofMinutes(1))

        val error = assertFailsWith<TooManyAttemptsException> { lockedAccount().ensureNotLocked(oneMinuteLater) }

        assertEquals("TOO_MANY_ATTEMPTS", error.code)
        assertEquals(429, error.status)
        assertEquals(AuthPolicy.LOGIN_LOCK_DURATION.minus(Duration.ofMinutes(1)), error.retryAfter)
    }

    @Test
    fun `LGN-01 잠금이 풀렸으면 로그인을 막지 않음`() {
        lockedAccount().ensureNotLocked(NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION))
    }

    @Test
    fun `LGN-01 로그인에 성공하면 실패 횟수와 잠금을 초기화`() {
        val reset = account(failedLoginCount = AuthPolicy.LOGIN_LOCK_THRESHOLD - 1, lockedUntil = NOW).resetLoginFailures()

        assertEquals(0, reset.failedLoginCount)
        assertNull(reset.lockedUntil)
    }

    private fun lockedAccount() = account(lockedUntil = NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION))

    private fun account(
        failedLoginCount: Int = 0,
        lockedUntil: Instant? = null,
    ) = Account(
        id = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f"),
        type = PrincipalType.EMPLOYEE,
        status = AccountStatus.ACTIVE,
        failedLoginCount = failedLoginCount,
        lockedUntil = lockedUntil,
        deactivatedAt = null,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
