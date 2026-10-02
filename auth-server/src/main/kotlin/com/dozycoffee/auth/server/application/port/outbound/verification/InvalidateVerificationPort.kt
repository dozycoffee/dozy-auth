package com.dozycoffee.auth.server.application.port.outbound.verification

import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import java.time.Instant
import java.util.UUID

/**
 * verification을 무효화합니다. 아직 소비·무효화되지 않은 토큰만 대상이며, 만료됐지만 무효화되지 않은 토큰도 포함합니다.
 * 이미 소비·무효화된 토큰의 시각은 바꾸지 않습니다.
 */
interface InvalidateVerificationPort {
    /**
     * 토큰 하나를 무효화합니다. GOV-09 owner 양도 취소에 씁니다.
     *
     * @return 무효화했으면 `true`. 없거나 이미 소비·무효화됐으면 `false`
     */
    fun invalidate(
        id: Long,
        now: Instant,
    ): Boolean

    /** [principalId]의 [purpose] 토큰을 무효화합니다. 무효화한 개수를 돌려줍니다. */
    fun invalidateAll(
        principalId: UUID,
        purpose: VerificationPurpose,
        now: Instant,
    ): Int

    /** ACC-04 비활성화할 때 [principalId]의 모든 목적의 토큰을 무효화합니다. 무효화한 개수를 돌려줍니다. */
    fun invalidateAll(
        principalId: UUID,
        now: Instant,
    ): Int
}
