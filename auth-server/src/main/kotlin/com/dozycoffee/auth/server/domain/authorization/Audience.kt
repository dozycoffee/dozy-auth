package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.core.RoleCode

/**
 * 토큰을 받는 서비스 (docs/domain.md §1). code는 등록 후 바꿀 수 없고, 삭제는 없습니다 (GOV-13).
 *
 * @property code `wms`, `catalog` 같은 audience code. 토큰의 `aud` 값이며 형식은 DOM-03
 */
data class Audience(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
) {
    init {
        require(RoleCode.isValidCode(code)) { "audience code 형식이 올바르지 않습니다: $code" }
    }
}
