package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.server.domain.authorization.Role
import java.time.Instant

/** role 정의의 이름과 설명을 바꿉니다 (GOV-13). system role인지는 호출하는 쪽이 먼저 확인합니다. */
interface UpdateRolePort {
    /** 바뀐 role. 없는 role이면 `null`입니다. */
    fun updateDetails(
        id: Long,
        name: String,
        description: String?,
        updatedAt: Instant,
    ): Role?
}
