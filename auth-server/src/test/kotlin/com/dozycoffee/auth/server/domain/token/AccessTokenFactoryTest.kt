package com.dozycoffee.auth.server.domain.token

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.support.TokenFixtures.EMPLOYEE
import com.dozycoffee.auth.server.support.TokenFixtures.ISSUER_BASE
import com.dozycoffee.auth.server.support.TokenFixtures.NOW
import com.dozycoffee.auth.server.support.TokenFixtures.PARTNER
import com.dozycoffee.auth.server.support.TokenFixtures.SESSION_ID
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import com.dozycoffee.auth.server.support.TokenFixtures.TOKEN_ID
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * token.md §3, §4.
 *
 * `iss` 형식, `aud` 값, 수명 같은 기대값은 명세 값을 그대로 씁니다.
 */
class AccessTokenFactoryTest {
    private fun create(
        principal: PrincipalKey = EMPLOYEE,
        realm: Realm = principal.type.realm,
        roles: List<String> = emptyList(),
        sessionId: String? = SESSION_ID,
    ) = AccessTokenFactory.create(principal, realm, roles.map(RoleCode::parse), sessionId, ISSUER_BASE, NOW, TOKEN_ID)

    @Test
    fun `iss는 realm별 issuer`() {
        assertEquals("https://auth.dozycoffee.com/realms/internal", create().issuer)
        assertEquals("https://auth.dozycoffee.com/realms/partner", create(principal = PARTNER).issuer)
    }

    @Test
    fun `직원의 aud는 보유한 role의 audience를 처음 나온 순서로 중복 없이`() {
        val claims = create(roles = listOf("wms:inbound_manager", "catalog:menu_editor", "wms:stock_viewer"))

        assertEquals(listOf("wms", "catalog"), claims.audience)
        assertEquals(listOf("wms:inbound_manager", "catalog:menu_editor", "wms:stock_viewer"), claims.roles)
    }

    @Test
    fun `role이 없는 직원의 aud는 빈 배열`() {
        assertEquals(emptyList(), create(roles = emptyList()).audience)
    }

    @Test
    fun `system token의 aud도 보유한 role의 audience이고 sid가 없음`() {
        val claims = create(principal = SYSTEM, roles = listOf("auth:partner_reader"), sessionId = null)

        assertEquals(listOf("auth"), claims.audience)
        assertNull(claims.sessionId)
    }

    @Test
    fun `파트너의 aud는 store 고정`() {
        assertEquals(listOf("store"), create(principal = PARTNER).audience)
    }

    @Test
    fun `DOM-04 파트너에게 role이 있으면 거부`() {
        assertFailsWith<IllegalArgumentException> { create(principal = PARTNER, roles = listOf("store:store_admin")) }
    }

    @Test
    fun `DOM-01 realm이 받을 수 없는 type이면 거부`() {
        assertFailsWith<IllegalArgumentException> { create(principal = EMPLOYEE, realm = Realm.PARTNER) }
        assertFailsWith<IllegalArgumentException> { create(principal = PARTNER, realm = Realm.INTERNAL) }
    }

    @Test
    fun `customer 토큰은 aud 규칙이 정해지지 않아 거부`() {
        assertFailsWith<IllegalArgumentException> { create(principal = PrincipalKey(PrincipalType.CUSTOMER, UUID.randomUUID())) }
    }

    @Test
    fun `system token에 세션 id가 있으면 거부`() {
        assertFailsWith<IllegalArgumentException> { create(principal = SYSTEM, sessionId = SESSION_ID) }
    }

    @Test
    fun `exp는 iat에서 policy access-token-ttl(10분) 뒤`() {
        val claims = create()

        assertEquals(NOW, claims.issuedAt)
        assertEquals(Duration.ofMinutes(10), Duration.between(claims.issuedAt, claims.expiresAt))
        assertEquals(TOKEN_ID, claims.tokenId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://auth.dozycoffee.com/", "https://auth.dozycoffee.com//"])
    fun `issuer 기준 주소 끝의 슬래시는 무시`(value: String) {
        assertEquals("https://auth.dozycoffee.com", IssuerBaseUri(value).value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["auth.dozycoffee.com", "ftp://auth.dozycoffee.com", ""])
    fun `issuer 기준 주소는 http나 https`(value: String) {
        assertFailsWith<IllegalArgumentException> { IssuerBaseUri(value) }
    }
}
