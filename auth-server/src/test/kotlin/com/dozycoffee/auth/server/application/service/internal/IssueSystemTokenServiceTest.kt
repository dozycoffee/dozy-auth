package com.dozycoffee.auth.server.application.service.internal

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.adapter.outbound.metrics.MetricsMicrometerAdapter
import com.dozycoffee.auth.server.application.port.inbound.internal.IssueSystemTokenCommand
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.client.LoadSystemClientPort
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.InvalidClientException
import com.dozycoffee.auth.server.domain.client.SystemClient
import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.dozycoffee.auth.server.support.TokenFixtures.FIXED_CLOCK
import com.dozycoffee.auth.server.support.TokenFixtures.ISSUER_BASE
import com.dozycoffee.auth.server.support.TokenFixtures.NOW
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import com.dozycoffee.auth.server.support.counted
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** system token 발급 (token.md §4, §8, api/internal.md 서비스 토큰 발급). */
class IssueSystemTokenServiceTest {
    private val loadSystemClient = mockk<LoadSystemClientPort>()
    private val loadAccount = mockk<LoadAccountPort>()
    private val loadPrincipalRoles = mockk<LoadPrincipalRolesPort>()
    private val signToken = mockk<SignTokenPort>()
    private val signed = slot<AccessTokenClaims>()
    private val meters = SimpleMeterRegistry()

    private val service =
        IssueSystemTokenService(
            loadSystemClient,
            loadAccount,
            loadPrincipalRoles,
            signToken,
            MetricsMicrometerAdapter(meters),
            ISSUER_BASE,
            FIXED_CLOCK,
        )

    init {
        every { loadSystemClient.findByClientId(ClientId(CLIENT_ID)) } returns
            SystemClient(SYSTEM.id, CLIENT_ID, SecretHash.of(SECRET), "Store", NOW, NOW)
        every { loadAccount.findAccountById(SYSTEM.id) } returns account()
        every { loadPrincipalRoles.findRoleCodes(SYSTEM.id) } returns
            listOf(RoleCode.parse("auth:partner_reader"), RoleCode.parse("catalog:menu_reader"))
        every { signToken.sign(capture(signed)) } returns "signed-token"
    }

    @Test
    fun `부여된 role로 aud와 roles를 정하고 세션 id 없이 서명`() {
        val issued = service.issue(IssueSystemTokenCommand(CLIENT_ID, SECRET))

        assertEquals("signed-token", issued.accessToken)
        assertEquals(AuthPolicy.ACCESS_TOKEN_TTL, issued.expiresIn)
        val claims = signed.captured
        assertEquals("${ISSUER_BASE.value}/realms/internal", claims.issuer)
        assertEquals(SYSTEM, claims.principal)
        assertEquals(listOf("auth", "catalog"), claims.audience)
        assertEquals(listOf("auth:partner_reader", "catalog:menu_reader"), claims.roles)
        assertEquals(NOW, claims.issuedAt)
        assertEquals(NOW.plus(AuthPolicy.ACCESS_TOKEN_TTL), claims.expiresAt)
        assertNull(claims.sessionId)
        assertEquals(1.0, meters.counted("dozy.auth.token.issued", "kind", "client_credentials", "realm", "internal"))
    }

    @Test
    fun `role이 없는 client는 aud와 roles가 비어 있는 토큰`() {
        every { loadPrincipalRoles.findRoleCodes(SYSTEM.id) } returns emptyList()

        service.issue(IssueSystemTokenCommand(CLIENT_ID, SECRET))

        assertEquals(emptyList(), signed.captured.audience)
        assertEquals(emptyList(), signed.captured.roles)
    }

    @Test
    fun `secret이 다르면 invalid_client`() {
        assertInvalidClient(IssueSystemTokenCommand(CLIENT_ID, "wrong-secret"))
    }

    @Test
    fun `등록되지 않은 client_id면 invalid_client`() {
        every { loadSystemClient.findByClientId(ClientId("svc-unknown")) } returns null

        assertInvalidClient(IssueSystemTokenCommand("svc-unknown", SECRET))
    }

    @Test
    fun `CLI-01 형식이 아닌 client_id는 조회하지 않고 invalid_client`() {
        assertInvalidClient(IssueSystemTokenCommand("deleted-${SYSTEM.id}", SECRET))

        verify { loadSystemClient wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(AccountStatus::class, names = ["ACTIVE"], mode = EnumSource.Mode.EXCLUDE)
    fun `ACTIVE가 아닌 client는 secret이 맞아도 invalid_client`(status: AccountStatus) {
        every { loadAccount.findAccountById(SYSTEM.id) } returns account(status = status)

        assertInvalidClient(IssueSystemTokenCommand(CLIENT_ID, SECRET))
    }

    @Test
    fun `DOM-01 system 타입이 아닌 principal의 client는 invalid_client`() {
        every { loadAccount.findAccountById(SYSTEM.id) } returns account(type = PrincipalType.EMPLOYEE)

        assertInvalidClient(IssueSystemTokenCommand(CLIENT_ID, SECRET))
    }

    @Test
    fun `SEC-03 명령과 결과를 문자열로 바꿔도 secret과 토큰이 드러나지 않음`() {
        val issued = service.issue(IssueSystemTokenCommand(CLIENT_ID, SECRET))

        assertFalse(IssueSystemTokenCommand(CLIENT_ID, SECRET).toString().contains(SECRET))
        assertFalse(issued.toString().contains("signed-token"))
    }

    private fun assertInvalidClient(command: IssueSystemTokenCommand) {
        val error = assertFailsWith<InvalidClientException> { service.issue(command) }

        assertEquals("invalid_client", error.code)
        assertEquals(401, error.status)
        verify(exactly = 0) { signToken.sign(any()) }
    }

    private fun account(
        type: PrincipalType = PrincipalType.SYSTEM,
        status: AccountStatus = AccountStatus.ACTIVE,
    ) = Account(SYSTEM.id, type, status, 0, null, null)

    private companion object {
        const val CLIENT_ID = "svc-store"
        const val SECRET = "dGVzdC1zZWNyZXQtZm9yLXN5c3RlbS1jbGllbnQtdGVzdA"
    }
}
