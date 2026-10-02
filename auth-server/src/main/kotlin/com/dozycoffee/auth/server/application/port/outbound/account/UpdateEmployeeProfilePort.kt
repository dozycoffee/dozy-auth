package com.dozycoffee.auth.server.application.port.outbound.account

import java.time.Instant
import java.util.UUID

/** 본인·관리자의 직원 정보 수정. 이메일은 바꾸지 않습니다 (ACC-07). */
interface UpdateEmployeeProfilePort {
    /** 바꿨으면 `true`, 직원 profile이 없으면 `false`입니다. */
    fun updateEmployeeProfile(
        principalId: UUID,
        name: String,
        phone: String?,
        address: String?,
        updatedAt: Instant,
    ): Boolean
}
