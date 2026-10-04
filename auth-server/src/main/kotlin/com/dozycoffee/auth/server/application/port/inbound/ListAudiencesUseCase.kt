package com.dozycoffee.auth.server.application.port.inbound

import com.dozycoffee.auth.server.domain.authorization.Audience

/** audience 목록 (api/admin.md audience 목록). */
interface ListAudiencesUseCase {
    /** 모든 audience. id 순서입니다. */
    fun listAudiences(): List<Audience>
}
