package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.application.port.outbound.client.LoadSystemClientPort
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.SystemClient
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Component

/**
 * system client를 저장합니다 (docs/data-model.md §3.4).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 * 등록·secret 재발급은 관리 API에서 추가합니다. 그 전까지는 운영 런북의 SQL로 등록합니다.
 */
@Component
class SystemClientPersistenceAdapter : LoadSystemClientPort {
    override fun findByClientId(clientId: ClientId): SystemClient? =
        SystemClientTable
            .selectAll()
            .where { SystemClientTable.clientId eq clientId.value }
            .singleOrNull()
            ?.toSystemClient()

    private fun ResultRow.toSystemClient() =
        SystemClient(
            principalId = this[SystemClientTable.principalId],
            clientId = this[SystemClientTable.clientId],
            secretHash = this[SystemClientTable.clientSecretHash]?.let(::SecretHash),
            name = this[SystemClientTable.name],
            secretRotatedAt = this[SystemClientTable.secretRotatedAt],
            createdAt = this[SystemClientTable.createdAt],
        )
}
