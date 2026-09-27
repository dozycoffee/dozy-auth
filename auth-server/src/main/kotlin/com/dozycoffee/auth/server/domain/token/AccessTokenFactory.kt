package com.dozycoffee.auth.server.domain.token

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.AuthPolicy
import java.time.Instant

/**
 * access token(system token 포함)의 claim을 조립합니다 (token.md §3, §4).
 *
 * 규칙을 어긴 입력은 호출하는 쪽의 버그이므로 [IllegalArgumentException]을 던집니다. 사용자 입력은 호출하는 쪽에서 먼저 검증합니다.
 */
object AccessTokenFactory {
    /** 파트너 토큰의 고정 `aud` (token.md §4). */
    private const val PARTNER_AUDIENCE = "store"

    /**
     * @param principal 토큰의 주체
     * @param realm 토큰을 발급하는 realm. [principal]의 종류를 받을 수 있어야 함 (DOM-01)
     * @param roles 보유한 role. 파트너는 비어 있어야 함 (DOM-04)
     * @param sessionId refresh 세션 id. system token은 `null`이어야 함
     * @param issuedAt 발급 시각. 서비스가 `Clock`으로 구해서 넘김
     * @param tokenId `jti`. 서비스가 만들어서 넘김
     */
    fun create(
        principal: PrincipalKey,
        realm: Realm,
        roles: List<RoleCode>,
        sessionId: String?,
        issuerBaseUri: IssuerBaseUri,
        issuedAt: Instant,
        tokenId: String,
    ): AccessTokenClaims {
        require(realm.allows(principal.type)) { "DOM-01 ${realm.pathValue} realm은 ${principal.type.claimValue} 토큰을 발급할 수 없습니다" }
        require(principal.type != PrincipalType.SYSTEM || sessionId == null) { "system token에는 세션 id가 없습니다" }

        return AccessTokenClaims(
            issuer = realm.issuer(issuerBaseUri.value),
            principal = principal,
            audience = audienceOf(principal.type, roles),
            roles = roles.map(RoleCode::value).distinct(),
            issuedAt = issuedAt,
            expiresAt = issuedAt.plus(AuthPolicy.ACCESS_TOKEN_TTL),
            tokenId = tokenId,
            sessionId = sessionId,
        )
    }

    /** token.md §4. 보유한 role의 audience 목록(처음 나온 순서, 중복 제거), 파트너는 `store` 고정. */
    private fun audienceOf(
        type: PrincipalType,
        roles: List<RoleCode>,
    ): List<String> =
        when (type) {
            PrincipalType.EMPLOYEE, PrincipalType.SYSTEM -> roles.map(RoleCode::audience).distinct()
            PrincipalType.PARTNER -> {
                require(roles.isEmpty()) { "DOM-04 파트너에게는 role을 부여하지 않습니다" }
                listOf(PARTNER_AUDIENCE)
            }
            PrincipalType.CUSTOMER -> throw IllegalArgumentException("customer 토큰의 aud 규칙은 아직 정의되지 않았습니다 (token.md §4)")
        }
}
