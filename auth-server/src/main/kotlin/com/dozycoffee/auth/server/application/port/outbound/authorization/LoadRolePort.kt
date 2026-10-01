package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.authorization.Role

/** role 정의를 조회합니다. */
interface LoadRolePort {
    /** 모든 role. [audienceCode]를 주면 그 audience의 role만입니다. id 순서입니다. */
    fun findRoles(audienceCode: String? = null): List<Role>

    fun findRoleById(id: Long): Role?

    fun findRoleByCode(code: RoleCode): Role?

    /** [codes] 중 정의된 role. 없는 code는 결과에서 빠지므로 호출하는 쪽이 개수로 `NOT_FOUND`를 판단합니다. */
    fun findRolesByCodes(codes: Collection<RoleCode>): List<Role>
}
