package com.dozycoffee.auth.server.application.port.outbound.session

import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.session.RefreshSession
import java.time.Instant

/**
 * SES-03 refresh token 교체. "현재 해시가 맞을 때만 교체"를 한 문장으로 처리합니다 (SES-04, data-model.md §3.10 갱신 쿼리).
 * 조회·비교·저장을 UseCase에서 나눠 조립하지 않습니다 (architecture.md §8).
 *
 * 교체 쿼리는 realm을 보지 않습니다. 요청 경로의 realm과 세션의 realm이 같은지(api/auth.md 토큰 갱신)는
 * `RefreshDecision.judge`가 확인하므로, 다른 realm의 세션을 교체하지 않으려면 판정 결과가 `Rotate`일 때 교체합니다.
 */
interface RotateRefreshSessionPort {
    /**
     * 현재 토큰 해시가 [presentedHash]이고 [now]에 살아 있는(폐기되지 않고 만료 전인) 세션이면 교체합니다.
     * 직전 해시 ← 현재 해시, 현재 해시 ← [newHash], 교체·사용 시각 ← [now],
     * 만료 ← `RefreshSession.extendedExpiresAt(now)`(절대 만료를 넘지 않음).
     *
     * @param now 애플리케이션 `Clock` 시각. DB의 `now()`를 쓰지 않습니다
     * @return 교체한 뒤의 세션. 교체하지 않았으면 `null`이며(다른 요청이 먼저 교체함, 만료, 폐기, 없음),
     *   호출하는 쪽은 [LoadRefreshSessionPort]로 다시 찾아 `RefreshDecision.judge`로 판정합니다
     */
    fun rotate(
        presentedHash: SecretHash,
        newHash: SecretHash,
        now: Instant,
    ): RefreshSession?
}
