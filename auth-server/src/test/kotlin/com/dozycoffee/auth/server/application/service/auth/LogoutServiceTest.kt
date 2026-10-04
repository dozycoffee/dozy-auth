package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.inbound.auth.LogoutCommand
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.session.RevokeReason
import com.dozycoffee.auth.server.support.FakeSessionPorts
import com.dozycoffee.auth.server.support.SessionFixtures.CURRENT_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.PREVIOUS_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.PRINCIPAL_ID
import com.dozycoffee.auth.server.support.SessionFixtures.ROTATED_AT
import com.dozycoffee.auth.server.support.SessionFixtures.SESSION_ID
import com.dozycoffee.auth.server.support.SessionFixtures.rotatedSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 로그아웃 (SES-06, SES-08, AUD-08). 실제 DB와 쿠키 삭제는 `LogoutApiTest`가 확인합니다. */
class LogoutServiceTest {
    private val sessions = FakeSessionPorts()
    private val loadAccount = mockk<LoadAccountPort>()
    private val recordAuditLog = mockk<RecordAuditLogPort>(relaxed = true)

    private val now: Instant = ROTATED_AT.plusSeconds(60)
    private val service =
        LogoutService(sessions, sessions, loadAccount, recordAuditLog, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `SES-06 쿠키의 세션을 LOGOUT으로 폐기하고 그 계정이 행위자인 SESSION_REVOKED를 남김`() {
        sessions.found = listOf(rotatedSession())
        every { loadAccount.findAccountById(PRINCIPAL_ID) } returns
            Account(PRINCIPAL_ID, PrincipalType.EMPLOYEE, AccountStatus.ACTIVE, 0, null, null)
        val event = slot<AuditEvent>()
        every { recordAuditLog.record(capture(event)) } returns Unit

        service.logout(command(CURRENT_TOKEN))

        assertEquals(listOf(CURRENT_TOKEN_HASH), sessions.lookups)
        assertEquals(listOf(FakeSessionPorts.Revocation(SESSION_ID, RevokeReason.LOGOUT, now)), sessions.revocations)
        assertEquals(AuditAction.SESSION_REVOKED, event.captured.action)
        assertEquals(AuditActor(PRINCIPAL_ID, PrincipalType.EMPLOYEE), event.captured.actor)
        assertEquals(AuditTarget.principal(PRINCIPAL_ID), event.captured.target)
        assertEquals(mapOf("realm" to "internal", "sessionId" to SESSION_ID.toString(), "reason" to "LOGOUT"), event.captured.detail)
    }

    @Test
    fun `SES-06 직전 토큰으로도 그 세션을 폐기`() {
        sessions.found = listOf(rotatedSession())
        every { loadAccount.findAccountById(PRINCIPAL_ID) } returns null

        service.logout(command(PREVIOUS_TOKEN))

        assertEquals(listOf(PREVIOUS_TOKEN_HASH), sessions.lookups)
        assertEquals(listOf(FakeSessionPorts.Revocation(SESSION_ID, RevokeReason.LOGOUT, now)), sessions.revocations)
    }

    @Test
    fun `SES-08 쿠키가 없거나 세션이 없으면 아무것도 하지 않고 끝남`() {
        service.logout(command(null))
        service.logout(command("unknown-refresh-token"))

        assertEquals(1, sessions.lookups.size)
        assertTrue(sessions.revocations.isEmpty())
        verify(exactly = 0) { recordAuditLog.record(any()) }
    }

    @Test
    fun `SES-08 이미 폐기·만료된 세션이면 기록하지 않고 끝남`() {
        sessions.found = listOf(rotatedSession())
        sessions.revoked = false

        service.logout(command(CURRENT_TOKEN))

        verify(exactly = 0) { recordAuditLog.record(any()) }
    }

    @Test
    fun `요청 경로와 다른 realm의 세션은 폐기하지 않음`() {
        sessions.found = listOf(rotatedSession(realm = Realm.INTERNAL))

        service.logout(command(CURRENT_TOKEN, realm = Realm.PARTNER))

        assertTrue(sessions.revocations.isEmpty())
    }

    private fun command(
        token: String?,
        realm: Realm = Realm.INTERNAL,
    ) = LogoutCommand(realm, token, "203.0.113.7", "DozyConsole/1.0")

    private companion object {
        /** `SessionFixtures`의 해시를 만든 원문. */
        const val CURRENT_TOKEN = "current-refresh-token"
        const val PREVIOUS_TOKEN = "previous-refresh-token"
    }
}
