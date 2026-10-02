package com.dozycoffee.auth.server.application.port.outbound.credential

import com.dozycoffee.auth.server.domain.credential.PasswordHash
import java.time.Instant
import java.util.UUID

/** principal의 첫 비밀번호를 저장합니다 (data-model.md §3.5). 초대 수락(VER-01 `EMPLOYEE_INVITATION`)에서 씁니다. */
interface CreatePasswordCredentialPort {
    /**
     * `changed_at`과 `created_at`은 [createdAt]입니다. 비밀번호가 아직 없는 principal에만 씁니다 (초대 수락 전 직원).
     * 이미 있으면 기본 키 충돌로 예외이며, 호출하는 쪽은 상태 확인(`PENDING`)과 verification 소비로 그런 호출을 먼저 막습니다.
     */
    fun createPasswordCredential(
        principalId: UUID,
        hash: PasswordHash,
        createdAt: Instant,
    )
}
