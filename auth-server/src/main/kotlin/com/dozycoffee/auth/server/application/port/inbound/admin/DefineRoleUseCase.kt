package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.PrincipalKey

/** 일반 role을 등록합니다 (api/admin.md role 등록, GOV-13). 등록만으로는 아무에게도 권한이 생기지 않습니다. */
interface DefineRoleUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.authorization.AudienceNotFoundException audience 없음
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException DB의 현재 role에 관리 등급이 없음 (GOV-14)
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleCodeDuplicatedException 같은 audience에 같은 code
     */
    fun defineRole(command: DefineRoleCommand): RoleDefinition
}

/**
 * role 등록 요청.
 *
 * @property manager 요청한 직원. `role.created_by`와 감사 로그의 행위자입니다. 관리 등급은 토큰이 아니라 DB의 현재 role로 정합니다
 * @property audienceCode audience code
 * @property code audience 안의 role code (DOM-03)
 */
data class DefineRoleCommand(
    val manager: PrincipalKey,
    val audienceCode: String,
    val code: String,
    val name: String,
    val description: String?,
    val ip: String?,
    val userAgent: String?,
)
