package com.dozycoffee.auth.server.application.port.outbound.account

import java.time.Instant
import java.util.UUID

/** LGN-01 5단계(로그인 성공), PWD-07(비밀번호 재설정): 로그인 실패 횟수와 잠금을 초기화합니다. */
interface ResetLoginFailuresPort {
    /** 초기화했으면 `true`, 계정이 없으면 `false`입니다. */
    fun resetLoginFailures(
        id: UUID,
        updatedAt: Instant,
    ): Boolean
}
