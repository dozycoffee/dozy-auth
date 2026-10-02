package com.dozycoffee.auth.server.application.port.outbound.session

import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.session.RefreshSession

/** 제시된 refresh token의 해시로 세션을 찾습니다. 판정은 `RefreshDecision.judge`가 합니다 (SES-03). */
interface LoadRefreshSessionPort {
    /**
     * 현재 토큰 해시나 직전 토큰 해시가 [tokenHash]인 세션. 만료·폐기된 세션도 돌려줍니다.
     * 일치하는 세션이 없으면 `null`입니다.
     */
    fun findSessionByTokenHash(tokenHash: SecretHash): RefreshSession?
}
