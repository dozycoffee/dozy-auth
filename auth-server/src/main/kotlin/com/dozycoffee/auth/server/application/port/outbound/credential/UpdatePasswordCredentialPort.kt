package com.dozycoffee.auth.server.application.port.outbound.credential

import com.dozycoffee.auth.server.domain.credential.PasswordHash
import java.time.Instant
import java.util.UUID

/** principal의 비밀번호를 바꿉니다 (data-model.md §3.5). 비밀번호 변경(PWD-06)에서 씁니다. */
interface UpdatePasswordCredentialPort {
    /**
     * `password_hash`를 [hash]로, `changed_at`을 [changedAt]으로 바꿉니다. `created_at`은 그대로 둡니다.
     *
     * @return 바꿨으면 `true`, 비밀번호가 없으면(초대 수락 전 직원, system client, 비활성화된 계정) `false`
     */
    fun updatePasswordHash(
        principalId: UUID,
        hash: PasswordHash,
        changedAt: Instant,
    ): Boolean
}
