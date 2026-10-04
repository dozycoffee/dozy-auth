package com.dozycoffee.auth.server.application.port.outbound.client

import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.ClientIdDuplicatedException
import com.dozycoffee.auth.server.domain.client.SystemClient
import java.time.Instant

/** system client를 등록합니다 (api/admin.md system client 등록, CLI-04). principal id는 DB가 UUIDv7으로 만듭니다. */
interface CreateSystemClientPort {
    /**
     * `system` 타입 `ACTIVE` principal과 `system_client`를 함께 만듭니다. `secret_rotated_at`은 [createdAt]입니다.
     * `client_id`가 겹치면 아무것도 남기지 않고 예외를 던지며, 호출한 트랜잭션은 계속 쓸 수 있습니다.
     *
     * @throws ClientIdDuplicatedException 같은 `client_id`의 system client가 이미 있을 때
     */
    fun createSystemClient(
        clientId: ClientId,
        name: String,
        secretHash: SecretHash,
        createdAt: Instant,
    ): SystemClient
}
