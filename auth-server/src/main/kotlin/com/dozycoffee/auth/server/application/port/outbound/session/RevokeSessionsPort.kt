package com.dozycoffee.auth.server.application.port.outbound.session

import com.dozycoffee.auth.server.domain.session.RevokeReason
import java.time.Instant
import java.util.UUID

/**
 * refresh 세션을 폐기합니다 (SES-06). 세션 폐기는 이 포트 하나로 모읍니다 (architecture.md §8).
 *
 * [now]에 살아 있는(폐기되지 않고 만료 전인) 세션만 폐기합니다. 이미 폐기된 세션의 폐기 시각과 사유는 바꾸지 않고,
 * 만료된 세션은 이미 쓸 수 없으므로 그대로 둡니다. 돌려주는 개수는 이번에 폐기한 세션만 셉니다
 * (AUD-08 `detail.revokedSessions`).
 *
 * 폐기해도 이미 발급된 access token은 만료까지 유효합니다 (SES-07).
 */
interface RevokeSessionsPort {
    /**
     * 세션 하나를 폐기합니다. 로그아웃(`LOGOUT`), 재사용 탐지(`REUSE_DETECTED`)에 씁니다.
     *
     * @return 폐기했으면 `true`. 세션이 없거나 이미 폐기·만료됐으면 `false`이며, 로그아웃은 그래도 성공입니다 (SES-08)
     */
    fun revokeSession(
        sessionId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Boolean

    /** principal의 모든 세션을 폐기합니다. PWD-07, ACC-03, ACC-04, GOV-09, GOV-12에 씁니다. 폐기한 세션 수를 돌려줍니다. */
    fun revokeAllSessions(
        principalId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Int

    /**
     * principal의 세션 중 [keepSessionId](현재 세션, 토큰의 `sid`)를 뺀 나머지를 폐기합니다 (PWD-06).
     * 폐기한 세션 수를 돌려줍니다.
     */
    fun revokeAllSessionsExcept(
        principalId: UUID,
        keepSessionId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Int
}
