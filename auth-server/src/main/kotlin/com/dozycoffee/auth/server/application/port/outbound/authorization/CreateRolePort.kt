package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.server.domain.authorization.Audience
import com.dozycoffee.auth.server.domain.authorization.Role
import com.dozycoffee.auth.server.domain.authorization.RoleCodeDuplicatedException
import java.time.Instant
import java.util.UUID

/** 일반 role을 등록합니다 (GOV-13). system role은 마이그레이션으로만 만듭니다. */
interface CreateRolePort {
    /**
     * @param code audience 안의 role code. 형식(DOM-03)은 호출하는 쪽이 검증합니다
     * @param createdBy 등록한 principal
     * @throws RoleCodeDuplicatedException 같은 audience에 같은 code의 role이 이미 있을 때
     */
    fun createRole(
        audience: Audience,
        code: String,
        name: String,
        description: String?,
        createdBy: UUID?,
        createdAt: Instant,
    ): Role
}
