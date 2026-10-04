package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.ListAudiencesUseCase
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadAudiencePort
import com.dozycoffee.auth.server.domain.authorization.Audience
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** audience 목록 (api/admin.md audience 목록). */
@Service
class ListAudiencesService(
    private val loadAudience: LoadAudiencePort,
) : ListAudiencesUseCase {
    @Transactional(readOnly = true)
    override fun listAudiences(): List<Audience> = loadAudience.findAudiences()
}
