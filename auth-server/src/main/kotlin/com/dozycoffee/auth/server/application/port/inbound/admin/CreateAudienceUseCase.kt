package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.server.domain.authorization.Audience

/** audience를 추가합니다 (api/admin.md audience 추가, GOV-13). owner만 할 수 있으며 등급은 DB의 현재 role로 정합니다 (GOV-14). */
interface CreateAudienceUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException DB의 현재 role이 owner가 아님 (GOV-13, GOV-14)
     * @throws com.dozycoffee.auth.server.domain.authorization.AudienceCodeDuplicatedException 같은 code
     */
    fun createAudience(command: CreateAudienceCommand): Audience
}

/**
 * audience 추가 요청.
 *
 * @property code audience code (DOM-03). 등록 후 바꿀 수 없습니다
 */
data class CreateAudienceCommand(
    val manager: PrincipalKey,
    val code: String,
    val name: String,
    val description: String?,
    val ip: String?,
    val userAgent: String?,
)
