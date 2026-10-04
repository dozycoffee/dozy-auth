package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.core.RoleCode

/** 관리 등급 (GOV-01). 가진 role로 정하며, 관리 작업을 하는 쪽과 대상 모두에 씁니다. */
enum class AdminGrade {
    /** `auth:owner`를 가짐. `auth:admin`을 함께 가져도 owner입니다. */
    OWNER,

    /** `auth:owner` 없이 `auth:admin`을 가짐. */
    ADMIN,

    /** system role이 없음. */
    NONE,
    ;

    companion object {
        /** 가진 role 목록에서 등급을 구합니다. */
        fun of(roles: Collection<RoleCode>): AdminGrade =
            when {
                SystemRoles.OWNER in roles -> OWNER
                SystemRoles.ADMIN in roles -> ADMIN
                else -> NONE
            }
    }
}
