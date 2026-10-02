package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.core.RoleCode

/** system role code (docs/domain.md §1, GOV-01). 정의는 마이그레이션 seed에 있습니다 (data-model.md §4). */
object SystemRoles {
    /** 정확히 한 명 (GOV-10). 부여는 부트스트랩(GOV-11)과 양도(GOV-09)로만 합니다. */
    val OWNER: RoleCode = RoleCode("auth", "owner")
}
