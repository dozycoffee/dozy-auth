package com.dozycoffee.auth.server.domain.session

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import java.time.Instant

/**
 * SES-03 refresh token 갱신 판정 결과. 결과가 여러 갈래인 정상 흐름이라 예외 대신 반환하고,
 * 갱신 UseCase가 교체·폐기와 응답(예외)으로 바꿉니다 (architecture.md §9.1).
 */
sealed interface RefreshDecision {
    /** 현재 토큰이고 세션이 살아 있음. 교체 포트로 원자적으로 교체합니다 (SES-04). */
    data class Rotate(
        val session: RefreshSession,
    ) : RefreshDecision

    /** 교체 후 `policy.rotation-grace` 이내의 직전 토큰. 세션을 유지하고 [TokenRotatedException]으로 응답합니다. */
    data object TokenRotated : RefreshDecision

    /**
     * 교체 후 `policy.rotation-grace`가 지난 직전 토큰. 재사용으로 보고 세션을 [RevokeReason.REUSE_DETECTED]로 폐기한 뒤
     * [SessionRevokedException]으로 응답합니다.
     */
    data class ReuseDetected(
        val session: RefreshSession,
    ) : RefreshDecision

    /** 일치하는 세션이 없거나 만료·폐기됨. [SessionExpiredException]으로 응답합니다. */
    data object Expired : RefreshDecision

    companion object {
        /**
         * 제시된 토큰의 해시 [presented]로 찾은 세션(현재 또는 직전 해시가 일치, 없으면 `null`)을 판정합니다.
         *
         * 판정 순서 (SES-03):
         * 1. 세션이 없거나, 요청한 [realm]의 세션이 아니면 `Expired` (api/auth.md 토큰 갱신)
         * 2. 폐기됐거나 만료됐으면 어느 토큰이든 `Expired`. 재사용 탐지는 살아 있는 세션에만 적용합니다
         * 3. 현재 토큰이면 `Rotate`
         * 4. 직전 토큰이면 교체 시각에서 `policy.rotation-grace`까지(경계 포함)는 `TokenRotated`, 그 뒤는 `ReuseDetected`
         *
         * 해시는 상수 시간으로 비교합니다 (SEC-05).
         */
        fun judge(
            session: RefreshSession?,
            presented: SecretHash,
            realm: Realm,
            now: Instant,
        ): RefreshDecision {
            if (session == null || session.realm != realm) return Expired
            if (!session.isAlive(now)) return Expired
            if (session.currentTokenHash.matches(presented)) return Rotate(session)
            val previous = session.previousTokenHash
            val rotatedAt = session.rotatedAt
            if (previous == null || rotatedAt == null || !previous.matches(presented)) return Expired
            return if (now.isAfter(rotatedAt.plus(AuthPolicy.ROTATION_GRACE))) ReuseDetected(session) else TokenRotated
        }
    }
}
