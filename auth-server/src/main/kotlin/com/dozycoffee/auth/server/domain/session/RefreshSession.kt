package com.dozycoffee.auth.server.domain.session

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * refresh 세션 하나 (SES-01, data-model.md §3.10). 로그인 한 번에 한 행이고, 갱신하면 같은 행의 토큰 해시를 교체합니다.
 *
 * 토큰 원문은 저장하지 않고 해시만 가집니다 (SES-02). 현재 시각은 호출하는 쪽이 `Clock`에서 읽어 넘깁니다.
 *
 * @property id DB가 만든 UUID. access token의 `sid`
 * @property currentTokenHash 지금 쓸 수 있는 refresh token의 해시
 * @property previousTokenHash 직전 refresh token의 해시. 한 번도 교체하지 않았으면 `null`
 * @property rotatedAt 마지막 교체 시각. 한 번도 교체하지 않았으면 `null`
 * @property createdAt 최초 로그인 시각
 * @property lastUsedAt 마지막 갱신 시각 (갱신 전에는 로그인 시각)
 * @property expiresAt 이 시각부터 만료. `policy.refresh-idle-ttl` 기준이며 [absoluteExpiresAt]을 넘지 않음
 * @property absoluteExpiresAt 최초 로그인 + `policy.refresh-absolute-ttl`. 연장되지 않음
 * @property ip 로그인 시 IP (`inet` 문자열)
 */
data class RefreshSession(
    val id: UUID,
    val principalId: UUID,
    val realm: Realm,
    val currentTokenHash: SecretHash,
    val previousTokenHash: SecretHash?,
    val rotatedAt: Instant?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val expiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val revokedAt: Instant?,
    val revokeReason: RevokeReason?,
    val userAgent: String?,
    val ip: String?,
) {
    init {
        require(!expiresAt.isAfter(absoluteExpiresAt)) { "만료 시각은 절대 만료 시각을 넘을 수 없습니다" }
        require((previousTokenHash == null) == (rotatedAt == null)) { "직전 토큰 해시와 교체 시각은 함께 있어야 합니다" }
        require((revokedAt == null) == (revokeReason == null)) { "폐기 시각과 폐기 사유는 함께 있어야 합니다" }
    }

    /** 폐기되지 않았고 만료 전인지. [expiresAt]부터는 만료입니다 (갱신 쿼리의 `expires_at > :now`와 같음). */
    fun isAlive(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)

    /**
     * SES-03 갱신으로 연장할 만료 시각. 갱신 시각에서 `policy.refresh-idle-ttl` 뒤이되 [absoluteExpiresAt]을 넘지 않습니다.
     * 영속성 어댑터의 갱신 쿼리(`least(..., absolute_expires_at)`)가 같은 값을 저장합니다.
     */
    fun extendedExpiresAt(now: Instant): Instant = minOf(idleExpiresAt(now), absoluteExpiresAt)

    /**
     * [now]부터 [absoluteExpiresAt]까지 남은 시간을 초 단위로 올린 값. refresh 쿠키의 `Max-Age`입니다 (api/conventions.md §6).
     * DB는 시각을 마이크로초까지만 저장하므로 1초 미만은 올립니다. 내리면 로그인 직후에도 `policy.refresh-absolute-ttl`보다
     * 1초 짧아집니다. 이미 지났으면 0입니다.
     */
    fun remainingAbsoluteLifetime(now: Instant): Duration {
        val remaining = Duration.between(now, absoluteExpiresAt)
        val seconds = remaining.seconds + if (remaining.nano > 0) 1 else 0
        return Duration.ofSeconds(maxOf(0L, seconds))
    }

    companion object {
        /** `policy.refresh-idle-ttl` 기준 만료 시각. 절대 만료로 줄이기 전의 값입니다. */
        fun idleExpiresAt(now: Instant): Instant = now.plus(AuthPolicy.REFRESH_IDLE_TTL)
    }
}

/**
 * 로그인으로 새로 만들 refresh 세션 (SES-01). id는 저장할 때 DB가 만듭니다.
 *
 * @property tokenHash 새 refresh token의 해시. 저장하면 현재 토큰 해시가 됨
 */
data class NewRefreshSession(
    val principalId: UUID,
    val realm: Realm,
    val tokenHash: SecretHash,
    val createdAt: Instant,
    val expiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val userAgent: String?,
    val ip: String?,
) {
    companion object {
        /** `user_agent` 컬럼 길이 (data-model.md §3.10). */
        const val USER_AGENT_MAX_LENGTH: Int = 255

        /**
         * [now]에 로그인한 세션. 만료는 `policy.refresh-idle-ttl`, 절대 만료는 `policy.refresh-absolute-ttl` 뒤입니다.
         * User-Agent는 기록용이라 컬럼 길이를 넘는 부분을 자릅니다.
         */
        fun start(
            principalId: UUID,
            realm: Realm,
            tokenHash: SecretHash,
            userAgent: String?,
            ip: String?,
            now: Instant,
        ): NewRefreshSession {
            val absoluteExpiresAt = now.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL)
            return NewRefreshSession(
                principalId = principalId,
                realm = realm,
                tokenHash = tokenHash,
                createdAt = now,
                expiresAt = minOf(RefreshSession.idleExpiresAt(now), absoluteExpiresAt),
                absoluteExpiresAt = absoluteExpiresAt,
                userAgent = userAgent?.take(USER_AGENT_MAX_LENGTH),
                ip = ip,
            )
        }
    }
}
