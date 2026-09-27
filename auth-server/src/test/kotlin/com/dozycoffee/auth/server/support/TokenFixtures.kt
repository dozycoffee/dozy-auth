package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.domain.token.AccessTokenClaims
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * 토큰 테스트에서 함께 쓰는 입력 데이터.
 *
 * 기대값(명세에 적힌 claim 이름, 형식 등)은 여기 두지 않고 각 테스트에 문자열 그대로 씁니다.
 */
object TokenFixtures {
    val ISSUER_BASE = IssuerBaseUri("https://auth.dozycoffee.com")

    val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    val EMPLOYEE = PrincipalKey(PrincipalType.EMPLOYEE, UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f"))
    val PARTNER = PrincipalKey(PrincipalType.PARTNER, UUID.fromString("0199a3c5-1d4f-7a8b-b2c6-5e9f0a3d7c21"))
    val SYSTEM = PrincipalKey(PrincipalType.SYSTEM, UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73"))

    const val SESSION_ID = "8c1d4f5f-2b9c-4e8a-a4d1-c9d3f2b7a6e0"
    const val TOKEN_ID = "5f2b9c1e-8a4d-4c1e-9d3f-2b7a6e0c1d4f"

    /** 서명할 claim. 조립 규칙(`AccessTokenFactory`)을 거치지 않고 값을 직접 채웁니다. 검사할 값만 바꿔 넘깁니다. */
    fun accessTokenClaims(
        principal: PrincipalKey = EMPLOYEE,
        audience: List<String> = listOf("wms", "catalog"),
        roles: List<String> = listOf("wms:inbound_manager", "catalog:menu_editor"),
        sessionId: String? = SESSION_ID,
    ) = AccessTokenClaims(
        issuer = "${ISSUER_BASE.value}/realms/${principal.type.realm.pathValue}",
        principal = principal,
        audience = audience,
        roles = roles,
        issuedAt = NOW,
        expiresAt = NOW.plusSeconds(600),
        tokenId = TOKEN_ID,
        sessionId = sessionId,
    )
}
