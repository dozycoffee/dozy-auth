package com.dozycoffee.auth.server.application.port.outbound.verification

import java.time.Instant

/**
 * verification을 소비합니다. 조회해서 `Verification.requireUsable`로 확인한 뒤에도 다른 요청이 먼저 소비했을 수 있으므로,
 * [now]에 살아 있을 때만 소비하는 것을 한 문장으로 처리합니다. 같은 토큰을 동시에 소비하면 하나만 성공합니다.
 */
interface ConsumeVerificationPort {
    /**
     * @return 소비했으면 `true`. 없거나 이미 살아 있지 않으면 아무것도 바꾸지 않고 `false`이며,
     *   호출하는 쪽은 `VERIFICATION_EXPIRED`로 응답합니다 (VER-04)
     */
    fun consume(
        id: Long,
        now: Instant,
    ): Boolean
}
