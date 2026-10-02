package com.dozycoffee.auth.server.application.port.outbound.verification

import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.verification.Verification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import java.time.Instant
import java.util.UUID

/** verification을 조회합니다. 살아 있는지의 판단은 `Verification.isLive`와 같습니다. */
interface LoadVerificationPort {
    /**
     * 토큰 해시로 찾습니다. 만료·소비·무효화된 토큰도 돌려주며, 쓸 수 있는지는 `Verification.requireUsable`로 확인합니다 (VER-04).
     * 요청으로 받은 원문은 `SecretHash.of`로 해시해서 넘깁니다.
     */
    fun findByTokenHash(tokenHash: SecretHash): Verification?

    /** [now]에 살아 있는 [principalId]의 [purpose] 토큰. GOV-11 부트스트랩에서 owner 초대가 살아 있는지 확인합니다. */
    fun findLive(
        principalId: UUID,
        purpose: VerificationPurpose,
        now: Instant,
    ): Verification?

    /**
     * [now]에 살아 있는 [purpose] 토큰 전체. 발급 순서입니다.
     * GOV-09 진행 중인 `OWNER_TRANSFER`가 있는지 확인하고, 양도를 취소할 때 무효화할 토큰을 찾습니다.
     */
    fun findLive(
        purpose: VerificationPurpose,
        now: Instant,
    ): List<Verification>
}
