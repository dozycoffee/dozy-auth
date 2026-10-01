package com.dozycoffee.auth.server.application.port.outbound.authorization

import com.dozycoffee.auth.server.domain.authorization.Audience
import com.dozycoffee.auth.server.domain.authorization.AudienceCodeDuplicatedException
import java.time.Instant

/** audience를 등록합니다 (GOV-13). */
interface CreateAudiencePort {
    /**
     * @param code 형식(DOM-03)은 호출하는 쪽이 검증합니다
     * @throws AudienceCodeDuplicatedException 같은 code의 audience가 이미 있을 때
     */
    fun createAudience(
        code: String,
        name: String,
        description: String?,
        createdAt: Instant,
    ): Audience
}
