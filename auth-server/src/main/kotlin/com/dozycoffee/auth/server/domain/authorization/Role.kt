package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.core.RoleCode

/**
 * role 정의 (docs/domain.md §1, GOV-13). audience와 code([RoleCode])는 등록 후 바꿀 수 없고 이름과 설명만 수정합니다.
 *
 * @property code `{audience}:{code}`
 * @property isSystem system role(`auth:owner`, `auth:admin`) 여부. system role은 수정·삭제할 수 없습니다 (GOV-13)
 */
data class Role(
    val id: Long,
    val code: RoleCode,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
)
