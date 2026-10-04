package com.dozycoffee.auth.server.application.port.outbound.client

import com.dozycoffee.auth.server.domain.SecretHash
import java.time.Instant
import java.util.UUID

/** CLI-03 system client의 secret 해시를 새 값으로 바꿉니다. 바꾸는 즉시 기존 secret으로는 인증되지 않습니다. */
interface RotateClientSecretPort {
    /** `client_secret_hash`와 `secret_rotated_at`을 바꿉니다. 바꿨으면 `true`, system client 행이 없으면 `false`입니다. */
    fun rotateSecret(
        principalId: UUID,
        secretHash: SecretHash,
        rotatedAt: Instant,
    ): Boolean
}
