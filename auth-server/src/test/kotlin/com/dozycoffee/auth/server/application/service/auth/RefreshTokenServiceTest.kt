package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.adapter.outbound.metrics.MetricsMicrometerAdapter
import com.dozycoffee.auth.server.application.port.inbound.auth.RefreshTokenCommand
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.session.RevokeReason
import com.dozycoffee.auth.server.domain.session.SessionExpiredException
import com.dozycoffee.auth.server.domain.session.SessionRevokedException
import com.dozycoffee.auth.server.domain.session.TokenRotatedException
import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.dozycoffee.auth.server.support.FakeSessionPorts
import com.dozycoffee.auth.server.support.SessionFixtures.CURRENT_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.LOGIN_AT
import com.dozycoffee.auth.server.support.SessionFixtures.PREVIOUS_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.PRINCIPAL_ID
import com.dozycoffee.auth.server.support.SessionFixtures.ROTATED_AT
import com.dozycoffee.auth.server.support.SessionFixtures.SESSION_ID
import com.dozycoffee.auth.server.support.SessionFixtures.rotatedSession
import com.dozycoffee.auth.server.support.TokenFixtures.ISSUER_BASE
import com.dozycoffee.auth.server.support.counted
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 토큰 갱신의 판정 연결 (SES-03~SES-05, AUD-08). 판정 규칙 자체는 `RefreshDecisionTest`, 실제 DB와 응답은 `RefreshTokenApiTest`가
 * 확인합니다.
 *
 * 세션은 `ROTATED_AT`에 한 번 교체한 상태(`SessionFixtures.rotatedSession`)이고, 시각은 고정한 `Clock`으로 정합니다.
 */
class RefreshTokenServiceTest {
    private val sessions = FakeSessionPorts()
    private val loadAccount = mockk<LoadAccountPort>()
    private val loadPrincipalRoles = mockk<LoadPrincipalRolesPort>()
    private val signToken = mockk<SignTokenPort>()
    private val recordAuditLog = mockk<RecordAuditLogPort>(relaxed = true)
    private val meters = SimpleMeterRegistry()

    private val inGrace: Instant = ROTATED_AT.plusSeconds(1)
    private val afterGrace: Instant = ROTATED_AT.plus(AuthPolicy.ROTATION_GRACE).plusSeconds(1)

    @Test
    fun `SES-03 현재 토큰이면 교체하고 세션 id를 담은 access token과 남은 절대 만료 시간을 돌려줌`() {
        val session = rotatedSession()
        sessions.found = listOf(session)
        sessions.rotated = session
        every { loadAccount.findAccountById(PRINCIPAL_ID) } returns account(AccountStatus.ACTIVE)
        every { loadPrincipalRoles.findRoleCodes(PRINCIPAL_ID) } returns listOf(RoleCode("wms", "inbound_manager"))
        val claims = slot<AccessTokenClaims>()
        every { signToken.sign(capture(claims)) } returns "signed"

        val result = service(inGrace).refresh(command(CURRENT_TOKEN))

        assertEquals("signed", result.accessToken)
        assertEquals(SESSION_ID.toString(), claims.captured.sessionId)
        assertEquals(listOf("wms:inbound_manager"), claims.captured.roles)
        assertEquals(inGrace, claims.captured.issuedAt)
        assertEquals(LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL).epochSecond - inGrace.epochSecond, result.refreshTokenMaxAge.seconds)
        assertEquals(listOf(FakeSessionPorts.Rotation(CURRENT_TOKEN_HASH, result.refreshToken.hash(), inGrace)), sessions.rotations)
        verify(exactly = 0) { recordAuditLog.record(any()) }
        assertEquals(1.0, meters.counted("dozy.auth.token.issued", "kind", "refresh", "realm", "internal"))
    }

    @Test
    fun `SES-03 유예 시간 안의 직전 토큰이면 교체하지 않고 TOKEN_ROTATED`() {
        sessions.found = listOf(rotatedSession())

        assertFailsWith<TokenRotatedException> { service(inGrace).refresh(command(PREVIOUS_TOKEN)) }

        assertEquals(listOf(PREVIOUS_TOKEN_HASH), sessions.lookups)
        assertTrue(sessions.rotations.isEmpty())
        assertTrue(sessions.revocations.isEmpty())
    }

    @Test
    fun `SES-03 유예 시간이 지난 직전 토큰이면 REUSE_DETECTED로 폐기하고 SESSION_REVOKED를 남긴 뒤 SESSION_REVOKED`() {
        sessions.found = listOf(rotatedSession())
        val event = slot<AuditEvent>()
        every { recordAuditLog.record(capture(event)) } returns Unit

        assertFailsWith<SessionRevokedException> { service(afterGrace).refresh(command(PREVIOUS_TOKEN)) }

        assertEquals(listOf(FakeSessionPorts.Revocation(SESSION_ID, RevokeReason.REUSE_DETECTED, afterGrace)), sessions.revocations)
        assertTrue(sessions.rotations.isEmpty())
        assertEquals(AuditAction.SESSION_REVOKED, event.captured.action)
        assertNull(event.captured.actor)
        assertEquals(AuditTarget.principal(PRINCIPAL_ID), event.captured.target)
        assertEquals(
            mapOf("realm" to "internal", "sessionId" to SESSION_ID.toString(), "reason" to "REUSE_DETECTED"),
            event.captured.detail,
        )
        assertEquals(IP, event.captured.ip)
        assertEquals(1.0, meters.counted("dozy.auth.refresh.reuse.detected", "realm", "internal"))
        assertEquals(0.0, meters.counted("dozy.auth.token.issued"))
    }

    @Test
    fun `SES-03 같은 재사용을 다른 요청이 먼저 폐기했으면 기록하지 않고 SESSION_EXPIRED`() {
        sessions.found = listOf(rotatedSession())
        sessions.revoked = false

        assertFailsWith<SessionExpiredException> { service(afterGrace).refresh(command(PREVIOUS_TOKEN)) }

        verify(exactly = 0) { recordAuditLog.record(any()) }
        assertEquals(0.0, meters.counted("dozy.auth.refresh.reuse.detected"))
    }

    @Test
    fun `SES-03 일치하는 세션이 없으면 SESSION_EXPIRED`() {
        assertFailsWith<SessionExpiredException> { service(inGrace).refresh(command("unknown-refresh-token")) }

        assertTrue(sessions.rotations.isEmpty())
    }

    @Test
    fun `SES-03 쿠키가 없거나 비어 있으면 세션을 찾지 않고 SESSION_EXPIRED`() {
        assertFailsWith<SessionExpiredException> { service(inGrace).refresh(command(null)) }
        assertFailsWith<SessionExpiredException> { service(inGrace).refresh(command("")) }

        assertTrue(sessions.lookups.isEmpty())
    }

    @Test
    fun `SES-03 요청 경로와 다른 realm의 세션이면 교체하지 않고 SESSION_EXPIRED`() {
        sessions.found = listOf(rotatedSession(realm = Realm.INTERNAL))

        assertFailsWith<SessionExpiredException> { service(inGrace).refresh(command(CURRENT_TOKEN, realm = Realm.PARTNER)) }

        assertTrue(sessions.rotations.isEmpty())
    }

    @Test
    fun `SES-05 계정이 ACTIVE가 아니면 교체하지 않고 세션을 따로 폐기하지 않으며 SESSION_EXPIRED`() {
        sessions.found = listOf(rotatedSession())
        every { loadAccount.findAccountById(PRINCIPAL_ID) } returns account(AccountStatus.SUSPENDED)

        assertFailsWith<SessionExpiredException> { service(inGrace).refresh(command(CURRENT_TOKEN)) }

        assertTrue(sessions.rotations.isEmpty())
        assertTrue(sessions.revocations.isEmpty())
    }

    @Test
    fun `SES-05 계정을 찾지 못하면 SESSION_EXPIRED`() {
        sessions.found = listOf(rotatedSession())
        every { loadAccount.findAccountById(PRINCIPAL_ID) } returns null

        assertFailsWith<SessionExpiredException> { service(inGrace).refresh(command(CURRENT_TOKEN)) }
    }

    @Test
    fun `SES-04 다른 요청이 먼저 교체해 교체가 0행이면 다시 찾아 판정하고 TOKEN_ROTATED`() {
        // 처음 찾았을 때는 현재 토큰이었지만, 교체하기 전에 다른 요청이 교체해 직전 토큰이 됨
        val beforeRace = rotatedSession().copy(currentTokenHash = RACED_TOKEN_HASH)
        val afterRace = rotatedSession().copy(previousTokenHash = RACED_TOKEN_HASH, rotatedAt = inGrace, lastUsedAt = inGrace)
        sessions.found = listOf(beforeRace, afterRace)
        sessions.rotated = null
        every { loadAccount.findAccountById(PRINCIPAL_ID) } returns account(AccountStatus.ACTIVE)

        assertFailsWith<TokenRotatedException> { service(inGrace).refresh(command(RACED_TOKEN)) }

        assertEquals(listOf(RACED_TOKEN_HASH, RACED_TOKEN_HASH), sessions.lookups)
        assertEquals(1, sessions.rotations.size)
    }

    @Test
    fun `SES-04 교체가 0행이고 다시 찾았을 때 폐기됐으면 SESSION_EXPIRED`() {
        sessions.found = listOf(rotatedSession(), rotatedSession(revokedAt = inGrace, revokeReason = RevokeReason.LOGOUT))
        sessions.rotated = null
        every { loadAccount.findAccountById(PRINCIPAL_ID) } returns account(AccountStatus.ACTIVE)

        assertFailsWith<SessionExpiredException> { service(inGrace).refresh(command(CURRENT_TOKEN)) }
    }

    private fun service(now: Instant) =
        RefreshTokenService(
            loadRefreshSession = sessions,
            rotateRefreshSession = sessions,
            revokeSessions = sessions,
            loadAccount = loadAccount,
            loadPrincipalRoles = loadPrincipalRoles,
            signToken = signToken,
            recordAuditLog = recordAuditLog,
            recordMetrics = MetricsMicrometerAdapter(meters),
            issuerBaseUri = ISSUER_BASE,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun command(
        token: String?,
        realm: Realm = Realm.INTERNAL,
    ) = RefreshTokenCommand(realm, token, IP, "DozyConsole/1.0")

    private fun account(status: AccountStatus) = Account(PRINCIPAL_ID, PrincipalType.EMPLOYEE, status, 0, null, null)

    private companion object {
        /** `SessionFixtures`의 해시를 만든 원문. */
        const val CURRENT_TOKEN = "current-refresh-token"
        const val PREVIOUS_TOKEN = "previous-refresh-token"

        /** 동시 교체 경쟁에서 진 요청이 제시한 토큰. */
        const val RACED_TOKEN = "raced-refresh-token"
        val RACED_TOKEN_HASH = SecretHash.of(RACED_TOKEN)

        const val IP = "203.0.113.7"
    }
}
