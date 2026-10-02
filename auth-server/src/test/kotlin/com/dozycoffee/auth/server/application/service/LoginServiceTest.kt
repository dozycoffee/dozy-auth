package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.inbound.LoginCommand
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.RecordLoginFailurePort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.credential.LoadPasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.crypto.VerifyPasswordPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.Employee
import com.dozycoffee.auth.server.domain.account.EmployeeProfile
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.credential.InvalidCredentialsException
import com.dozycoffee.auth.server.domain.credential.PasswordHash
import com.dozycoffee.auth.server.domain.credential.RawPassword
import com.dozycoffee.auth.server.support.TokenFixtures.FIXED_CLOCK
import com.dozycoffee.auth.server.support.TokenFixtures.ISSUER_BASE
import com.dozycoffee.auth.server.support.TokenFixtures.NOW
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 로그인 검증 순서 (LGN-01, LGN-02). 저장과 응답은 `LoginApiTest`가 실제 DB로 확인합니다. */
class LoginServiceTest {
    private val loadEmployee = mockk<LoadEmployeePort>()
    private val loadPasswordCredential = mockk<LoadPasswordCredentialPort>()
    private val verifyPassword = mockk<VerifyPasswordPort>()
    private val recordLoginFailure = mockk<RecordLoginFailurePort>()
    private val recordAuditLog = mockk<RecordAuditLogPort>(relaxed = true)

    private val service =
        LoginService(
            loadEmployee = loadEmployee,
            loadPasswordCredential = loadPasswordCredential,
            verifyPassword = verifyPassword,
            recordLoginFailure = recordLoginFailure,
            resetLoginFailures = mockk(),
            createRefreshSession = mockk(),
            loadPrincipalRoles = mockk(),
            signToken = mockk(),
            recordAuditLog = recordAuditLog,
            issuerBaseUri = ISSUER_BASE,
            clock = FIXED_CLOCK,
        )

    @Test
    fun `LGN-01 잠긴 계정은 비밀번호를 검증하기 전에 거부하고 아무것도 기록하지 않음`() {
        every { loadEmployee.findEmployeeByEmail(Email(EMAIL)) } returns employee(lockedUntil = NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION))

        val error = assertFailsWith<TooManyAttemptsException> { service.login(command()) }

        assertEquals(AuthPolicy.LOGIN_LOCK_DURATION, error.retryAfter)
        verify(exactly = 0) { verifyPassword.verify(any(), any()) }
        verify(exactly = 0) { recordAuditLog.record(any()) }
    }

    @Test
    fun `LGN-02 없는 계정도 가짜 해시로 비밀번호 검증을 수행`() {
        every { loadEmployee.findEmployeeByEmail(Email(EMAIL)) } returns null
        every { verifyPassword.verify(any(), null) } returns false

        assertFailsWith<InvalidCredentialsException> { service.login(command()) }

        verify(exactly = 1) { verifyPassword.verify(RawPassword(PASSWORD), null) }
    }

    @Test
    fun `AUD-08 없는 계정의 실패는 행위자 없이 realm만 남김`() {
        every { loadEmployee.findEmployeeByEmail(Email(EMAIL)) } returns null
        every { verifyPassword.verify(any(), null) } returns false
        val event = slot<AuditEvent>()
        every { recordAuditLog.record(capture(event)) } returns Unit

        assertFailsWith<InvalidCredentialsException> { service.login(command()) }

        assertEquals(AuditAction.LOGIN_FAILED, event.captured.action)
        assertEquals(null, event.captured.actor)
        assertEquals(mapOf("realm" to "internal"), event.captured.detail)
    }

    @Test
    fun `LGN-01 비밀번호가 틀리면 실패를 기록한 뒤 INVALID_CREDENTIALS`() {
        val employee = employee()
        every { loadEmployee.findEmployeeByEmail(Email(EMAIL)) } returns employee
        every { loadPasswordCredential.findPasswordHash(employee.account.id) } returns HASH
        every { verifyPassword.verify(RawPassword(PASSWORD), HASH) } returns false
        every { recordLoginFailure.recordLoginFailure(employee.account.id, NOW) } returns employee.account.recordLoginFailure(NOW)

        assertFailsWith<InvalidCredentialsException> { service.login(command()) }

        verify(exactly = 1) { recordLoginFailure.recordLoginFailure(employee.account.id, NOW) }
    }

    private fun command() = LoginCommand(Realm.INTERNAL, EMAIL, RawPassword(PASSWORD), "203.0.113.7", "DozyConsole/1.0")

    private fun employee(lockedUntil: java.time.Instant? = null): Employee {
        val id = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")
        return Employee(
            Account(id, PrincipalType.EMPLOYEE, AccountStatus.ACTIVE, 0, lockedUntil, null),
            EmployeeProfile(id, Email(EMAIL), "김도윤", null, null),
        )
    }

    private companion object {
        const val EMAIL = "kim@dozycoffee.com"
        const val PASSWORD = "correct-horse-battery"
        val HASH = PasswordHash("\$argon2id\$v=19\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA")
    }
}
