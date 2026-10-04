package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.IssueDevTokenCommand
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.dozycoffee.auth.server.domain.token.InvalidTokenRequestException
import com.dozycoffee.auth.server.support.TokenFixtures.EMPLOYEE
import com.dozycoffee.auth.server.support.TokenFixtures.FIXED_CLOCK
import com.dozycoffee.auth.server.support.TokenFixtures.ISSUER_BASE
import com.dozycoffee.auth.server.support.TokenFixtures.NOW
import com.dozycoffee.auth.server.support.TokenFixtures.PARTNER
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 개발용 토큰 발급 (api/dev.md, token.md §3, §4). */
class IssueDevTokenServiceTest {
    private val signToken = mockk<SignTokenPort>()
    private val signed = slot<AccessTokenClaims>()

    private val service = IssueDevTokenService(signToken, ISSUER_BASE, FIXED_CLOCK)

    init {
        every { signToken.sign(capture(signed)) } returns "signed-token"
    }

    @Test
    fun `직원 토큰은 요청한 role로 aud와 roles를 정하고 세션 id 없이 서명`() {
        val issued = service.issue(command(roles = listOf("wms:inbound_manager", "catalog:menu_editor", "wms:stock_viewer")))

        assertEquals("signed-token", issued.accessToken)
        assertEquals(AuthPolicy.ACCESS_TOKEN_TTL, issued.expiresIn)
        val claims = signed.captured
        assertEquals("${ISSUER_BASE.value}/realms/internal", claims.issuer)
        assertEquals(EMPLOYEE, claims.principal)
        assertEquals(listOf("wms", "catalog"), claims.audience)
        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor", "wms:stock_viewer"), claims.roles)
        assertEquals(NOW, claims.issuedAt)
        assertNull(claims.sessionId)
    }

    @Test
    fun `role 없이 요청한 직원 토큰은 aud가 비어 있음`() {
        service.issue(command(roles = emptyList()))

        assertEquals(emptyList(), signed.captured.audience)
        assertEquals(emptyList(), signed.captured.roles)
    }

    @Test
    fun `system 토큰은 role의 audience를 aud로 하고 세션 id가 없음`() {
        service.issue(command(principalType = "system", principalId = SYSTEM.id.toString(), roles = listOf("catalog:menu_reader")))

        assertEquals(SYSTEM, signed.captured.principal)
        assertEquals(listOf("catalog"), signed.captured.audience)
        assertNull(signed.captured.sessionId)
    }

    @Test
    fun `파트너 토큰은 partner realm에서 aud가 store`() {
        service.issue(command(realm = "partner", principalType = "partner", principalId = PARTNER.id.toString(), roles = emptyList()))

        assertEquals("${ISSUER_BASE.value}/realms/partner", signed.captured.issuer)
        assertEquals(PARTNER, signed.captured.principal)
        assertEquals(listOf("store"), signed.captured.audience)
        assertEquals(emptyList(), signed.captured.roles)
        assertNull(signed.captured.sessionId)
    }

    @Test
    fun `DOM-04 파트너 토큰에 role이 있으면 VALIDATION_FAILED`() {
        assertValidationFailed(
            command(realm = "partner", principalType = "partner", principalId = PARTNER.id.toString(), roles = listOf("store:owner")),
        )
    }

    @ParameterizedTest
    @CsvSource("internal, partner", "partner, employee", "partner, system", "customer, customer", "internal, customer")
    fun `DOM-01 허용하지 않는 realm과 principal type 조합은 VALIDATION_FAILED`(
        realm: String,
        principalType: String,
    ) {
        assertValidationFailed(command(realm = realm, principalType = principalType, roles = emptyList()))
    }

    @ParameterizedTest
    @CsvSource("unknown, employee", "INTERNAL, employee", "internal, EMPLOYEE", "internal, admin")
    fun `모르는 realm이나 principal type은 VALIDATION_FAILED`(
        realm: String,
        principalType: String,
    ) {
        assertValidationFailed(command(realm = realm, principalType = principalType))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0199A3C4-7B2E-7C1A-9F3D-2B6E8A1C4D5F", "0199a3c47b2e7c1a9f3d2b6e8a1c4d5f", "1-1-1-1-1", ""])
    fun `principalId가 소문자 하이픈 포함 UUID가 아니면 VALIDATION_FAILED`(principalId: String) {
        assertValidationFailed(command(principalId = principalId))
    }

    @ParameterizedTest
    @ValueSource(strings = ["wms", "wms:", ":inbound_manager", "WMS:inbound_manager", "wms:inbound-manager", "wms:a:b", ""])
    fun `DOM-03 role 형식이 틀리면 VALIDATION_FAILED`(role: String) {
        assertValidationFailed(command(roles = listOf("wms:inbound_manager", role)))
    }

    private fun assertValidationFailed(command: IssueDevTokenCommand) {
        val error = assertFailsWith<InvalidTokenRequestException> { service.issue(command) }

        assertEquals("VALIDATION_FAILED", error.code)
        assertEquals(400, error.status)
        verify(exactly = 0) { signToken.sign(any()) }
    }

    private fun command(
        realm: String = "internal",
        principalType: String = "employee",
        principalId: String = EMPLOYEE.id.toString(),
        roles: List<String> = listOf("wms:inbound_manager"),
    ) = IssueDevTokenCommand(realm, principalType, principalId, roles)
}
