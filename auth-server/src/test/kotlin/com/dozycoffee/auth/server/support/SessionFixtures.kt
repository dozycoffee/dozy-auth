package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.session.RefreshSession
import com.dozycoffee.auth.server.domain.session.RevokeReason
import java.time.Instant
import java.util.UUID

/**
 * refresh 세션 테스트에서 함께 쓰는 입력 데이터.
 *
 * 기대값(정책 시간)은 여기 두지 않고 각 테스트에서 `AuthPolicy`로 씁니다.
 */
object SessionFixtures {
    /** 최초 로그인 시각 */
    val LOGIN_AT: Instant = Instant.parse("2026-09-25T00:00:00Z")

    /** 교체한 시각. 최초 로그인 한 시간 뒤 */
    val ROTATED_AT: Instant = LOGIN_AT.plusSeconds(3600)

    val SESSION_ID: UUID = UUID.fromString("8c1d4f5f-2b9c-4e8a-a4d1-c9d3f2b7a6e0")
    val PRINCIPAL_ID: UUID = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")

    val CURRENT_TOKEN_HASH: SecretHash = SecretHash.of("current-refresh-token")
    val PREVIOUS_TOKEN_HASH: SecretHash = SecretHash.of("previous-refresh-token")
    val NEXT_TOKEN_HASH: SecretHash = SecretHash.of("next-refresh-token")
    val UNKNOWN_TOKEN_HASH: SecretHash = SecretHash.of("unknown-refresh-token")

    /**
     * [ROTATED_AT]에 한 번 교체한 세션. 직전 토큰은 [PREVIOUS_TOKEN_HASH], 현재 토큰은 [CURRENT_TOKEN_HASH]입니다.
     * 검사할 값만 바꿔 넘깁니다.
     */
    fun rotatedSession(
        realm: Realm = Realm.INTERNAL,
        rotatedAt: Instant = ROTATED_AT,
        expiresAt: Instant = rotatedAt.plus(AuthPolicy.REFRESH_IDLE_TTL),
        absoluteExpiresAt: Instant = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL),
        revokedAt: Instant? = null,
        revokeReason: RevokeReason? = null,
    ) = RefreshSession(
        id = SESSION_ID,
        principalId = PRINCIPAL_ID,
        realm = realm,
        currentTokenHash = CURRENT_TOKEN_HASH,
        previousTokenHash = PREVIOUS_TOKEN_HASH,
        rotatedAt = rotatedAt,
        createdAt = LOGIN_AT,
        lastUsedAt = rotatedAt,
        expiresAt = expiresAt,
        absoluteExpiresAt = absoluteExpiresAt,
        revokedAt = revokedAt,
        revokeReason = revokeReason,
        userAgent = "DozyApp/1.0",
        ip = "203.0.113.7",
    )
}
