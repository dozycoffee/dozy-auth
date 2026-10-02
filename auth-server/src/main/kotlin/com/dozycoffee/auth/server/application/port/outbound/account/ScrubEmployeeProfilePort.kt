package com.dozycoffee.auth.server.application.port.outbound.account

import java.time.Instant
import java.util.UUID

/**
 * ACC-04 비활성화할 때 직원 profile의 개인정보를 파기합니다. 값은 `EmployeeProfile.scrubbed`입니다.
 * 상태 변경과 다른 정리 작업은 UseCase가 같은 트랜잭션에서 묶습니다.
 */
interface ScrubEmployeeProfilePort {
    /** 파기했으면 `true`, 직원 profile이 없으면 `false`입니다. */
    fun scrubEmployeeProfile(
        principalId: UUID,
        scrubbedAt: Instant,
    ): Boolean
}
