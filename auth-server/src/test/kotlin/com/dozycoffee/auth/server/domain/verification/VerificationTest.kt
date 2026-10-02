package com.dozycoffee.auth.server.domain.verification

import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.SecretHash
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** verification 발급과 검증 (domain.md §7 VER). 유효 시간은 `AuthPolicy`, 에러 코드는 api/conventions.md §11의 문자열 그대로입니다. */
class VerificationTest {
    @Test
    fun `VER-01 목적별 유효 시간은 정책 값`() {
        assertEquals(AuthPolicy.INVITATION_TTL, issue(VerificationPurpose.EMPLOYEE_INVITATION).verification.ttl())
        assertEquals(AuthPolicy.SIGNUP_VERIFICATION_TTL, issue(VerificationPurpose.SIGNUP_VERIFICATION).verification.ttl())
        assertEquals(AuthPolicy.PASSWORD_RESET_TTL, issue(VerificationPurpose.PASSWORD_RESET).verification.ttl())
        assertEquals(AuthPolicy.OWNER_TRANSFER_TTL, issue(VerificationPurpose.OWNER_TRANSFER).verification.ttl())
    }

    @Test
    fun `VER-01 지금 목적은 모두 이메일로 보내는 링크 토큰`() {
        VerificationPurpose.entries.forEach {
            val issued = issue(it).verification
            assertEquals(VerificationMethod.EMAIL, issued.method)
            assertNull(issued.maxAttempts)
        }
    }

    @Test
    fun `VER-01 이메일 변경은 추후 기능이라 목적에 없음`() {
        assertTrue(VerificationPurpose.entries.none { it.name == "EMAIL_CHANGE" })
    }

    @Test
    fun `VER-02 저장할 값에는 원문이 아니라 원문의 SHA-256 해시를 담음`() {
        val issued = issue(VerificationPurpose.PASSWORD_RESET)

        assertEquals(SecretHash.of(issued.token.value), issued.verification.tokenHash)
        assertNotEquals(issued.token.value, issued.verification.tokenHash.hex)
    }

    @Test
    fun `VER-02 발급할 때마다 다른 토큰`() {
        val first = issue(VerificationPurpose.PASSWORD_RESET)
        val second = issue(VerificationPurpose.PASSWORD_RESET)

        assertNotEquals(first.token.value, second.token.value)
    }

    @Test
    fun `VER-08 발송 대상 주소와 payload를 발급 값에 남김`() {
        val issued =
            NewVerification
                .issue(PRINCIPAL_ID, VerificationPurpose.OWNER_TRANSFER, TARGET, NOW, payload = mapOf("requestedBy" to "owner-id"))
                .verification

        assertEquals(PRINCIPAL_ID, issued.principalId)
        assertEquals(TARGET, issued.target)
        assertEquals(mapOf("requestedBy" to "owner-id"), issued.payload)
        assertEquals(NOW, issued.createdAt)
    }

    @Test
    fun `SEC-03 발급 결과를 문자열로 바꿔도 토큰 원문이 나오지 않음`() {
        val issued = issue(VerificationPurpose.EMPLOYEE_INVITATION)

        assertFalse(issued.toString().contains(issued.token.value))
        assertFalse(issued.token.toString().contains(issued.token.value))
    }

    @Test
    fun `VER-04 만료 전이고 소비·무효화되지 않은 토큰은 쓸 수 있음`() {
        val verification = verification()

        assertTrue(verification.isLive(NOW))
        assertSame(verification, Verification.requireUsable(verification, VerificationPurpose.PASSWORD_RESET, NOW))
        assertTrue(verification.isLive(verification.expiresAt.minusNanos(1)))
    }

    @Test
    fun `VER-04 만료 시각이 되면 VERIFICATION_EXPIRED`() {
        val verification = verification()

        assertFalse(verification.isLive(verification.expiresAt))
        assertExpired { verification.ensureUsable(VerificationPurpose.PASSWORD_RESET, verification.expiresAt) }
    }

    @Test
    fun `VER-04 사용한 토큰은 VERIFICATION_EXPIRED`() {
        val verification = verification(consumedAt = NOW)

        assertFalse(verification.isLive(NOW))
        assertExpired { verification.ensureUsable(VerificationPurpose.PASSWORD_RESET, NOW) }
    }

    @Test
    fun `VER-04 무효화된 토큰은 VERIFICATION_EXPIRED`() {
        val verification = verification(invalidatedAt = NOW)

        assertFalse(verification.isLive(NOW))
        assertExpired { verification.ensureUsable(VerificationPurpose.PASSWORD_RESET, NOW) }
    }

    @Test
    fun `VER-04 없는 토큰도 VERIFICATION_EXPIRED`() {
        assertExpired { Verification.requireUsable(null, VerificationPurpose.PASSWORD_RESET, NOW) }
    }

    @Test
    fun `VER-04 다른 목적으로 발급한 토큰은 VERIFICATION_EXPIRED`() {
        val verification = verification(purpose = VerificationPurpose.PASSWORD_RESET)

        assertExpired { Verification.requireUsable(verification, VerificationPurpose.EMPLOYEE_INVITATION, NOW) }
    }

    @Test
    fun `시도 횟수 상한이 있는 토큰은 상한까지 시도하면 VERIFICATION_EXPIRED`() {
        assertTrue(verification(attemptCount = 2, maxAttempts = 3).isLive(NOW))

        val exhausted = verification(attemptCount = 3, maxAttempts = 3)

        assertTrue(exhausted.attemptsExhausted)
        assertExpired { exhausted.ensureUsable(VerificationPurpose.PASSWORD_RESET, NOW) }
    }

    @Test
    fun `링크 토큰은 시도 횟수를 보지 않음`() {
        val verification = verification(attemptCount = 100, maxAttempts = null)

        assertFalse(verification.attemptsExhausted)
        assertTrue(verification.isLive(NOW))
    }

    private fun issue(purpose: VerificationPurpose) = NewVerification.issue(PRINCIPAL_ID, purpose, TARGET, NOW)

    private fun NewVerification.ttl() = Duration.between(createdAt, expiresAt)

    private fun assertExpired(block: () -> Unit) {
        val error = assertFailsWith<VerificationExpiredException>(block = block)
        assertEquals("VERIFICATION_EXPIRED", error.code)
        assertEquals(410, error.status)
    }

    private fun verification(
        purpose: VerificationPurpose = VerificationPurpose.PASSWORD_RESET,
        attemptCount: Int = 0,
        maxAttempts: Int? = null,
        consumedAt: Instant? = null,
        invalidatedAt: Instant? = null,
    ) = Verification(
        id = 1,
        principalId = PRINCIPAL_ID,
        purpose = purpose,
        method = VerificationMethod.EMAIL,
        target = TARGET,
        tokenHash = SecretHash.of("token"),
        payload = emptyMap(),
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
        expiresAt = NOW.plus(AuthPolicy.PASSWORD_RESET_TTL),
        consumedAt = consumedAt,
        invalidatedAt = invalidatedAt,
        createdAt = NOW.minusSeconds(60),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        val PRINCIPAL_ID: UUID = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")
        val TARGET = Email("Kim.Barista@DozyCoffee.com")
    }
}
