package com.dozycoffee.auth.server.application.port.outbound.credential

import com.dozycoffee.auth.server.domain.credential.PasswordHash
import java.util.UUID

/** principal의 비밀번호 해시를 조회합니다 (data-model.md §3.5). LGN-01 3단계에서 씁니다. */
interface LoadPasswordCredentialPort {
    /** 비밀번호가 없으면(초대 수락 전 직원, system client) `null`입니다. */
    fun findPasswordHash(principalId: UUID): PasswordHash?
}
