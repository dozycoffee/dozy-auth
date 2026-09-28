package com.dozycoffee.auth.server.domain.token

import com.dozycoffee.auth.core.PrincipalKey
import java.time.Instant

/**
 * access token(system token 포함)에 담을 claim (token.md §3).
 *
 * 값을 정하는 규칙(`aud` 결정, 수명 등)은 이 타입을 만드는 쪽이 책임집니다.
 *
 * @property issuer `iss`. `{issuer-base}/realms/{realm}`
 * @property principal `sub`, `principalType`, `principalId`
 * @property audience `aud`
 * @property roles `{audience}:{code}` 목록
 * @property issuedAt `iat`
 * @property expiresAt `exp`
 * @property tokenId `jti`
 * @property sessionId `sid`. system token은 `null`
 */
data class AccessTokenClaims(
    val issuer: String,
    val principal: PrincipalKey,
    val audience: List<String>,
    val roles: List<String>,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val tokenId: String,
    val sessionId: String?,
) {
    init {
        require(expiresAt.isAfter(issuedAt)) { "만료 시각은 발급 시각보다 뒤여야 합니다" }
    }
}
