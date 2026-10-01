package com.dozycoffee.auth.server.application.port.outbound.account

import com.dozycoffee.auth.server.domain.account.LoginFailureResult
import java.time.Instant
import java.util.UUID

/**
 * LGN-01 3단계: 로그인 실패를 기록하고 기준에 도달하면 잠급니다. 규칙은 `Account.recordLoginFailure`입니다.
 *
 * 같은 계정에 동시에 실패해도 횟수를 잃지 않도록 행을 잠그고 읽은 뒤 같은 트랜잭션에서 저장합니다.
 * 조회·판단·저장을 UseCase에서 나눠 조립하지 않습니다 (architecture.md §8).
 */
interface RecordLoginFailurePort {
    /** 기록한 결과. 계정이 없으면 `null`입니다. */
    fun recordLoginFailure(
        id: UUID,
        now: Instant,
    ): LoginFailureResult?
}
