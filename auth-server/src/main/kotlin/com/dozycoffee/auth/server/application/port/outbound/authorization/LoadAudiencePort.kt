package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.server.domain.authorization.Audience

/** audience를 조회합니다. */
interface LoadAudiencePort {
    /** 모든 audience. id 순서입니다. */
    fun findAudiences(): List<Audience>

    fun findAudienceById(id: Long): Audience?

    fun findAudienceByCode(code: String): Audience?
}
