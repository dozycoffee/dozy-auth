package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.application.port.outbound.client.CreateSystemClientPort
import com.dozycoffee.auth.server.application.port.outbound.client.LoadSystemClientPort
import com.dozycoffee.auth.server.application.port.outbound.client.RotateClientSecretPort
import com.dozycoffee.auth.server.application.port.outbound.client.ScrubSystemClientPort
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.ClientIdDuplicatedException
import com.dozycoffee.auth.server.domain.client.SystemClient
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * system client를 저장합니다 (docs/data-model.md §3.4).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 *
 * - 등록은 직원 초대처럼 principal을 만든 뒤 `system_client`를 `INSERT ... ON CONFLICT DO NOTHING`으로 넣습니다. 예외로 받으면
 *   PostgreSQL이 트랜잭션 전체를 중단 상태로 만들기 때문입니다. 기본 키는 방금 만든 principal이라, 넣은 행이 없으면 `client_id` 중복입니다.
 * - secret 원문은 받지 않고 해시만 저장합니다 (SEC-01).
 */
@Component
class SystemClientPersistenceAdapter :
    LoadSystemClientPort,
    CreateSystemClientPort,
    RotateClientSecretPort,
    ScrubSystemClientPort {
    override fun findByClientId(clientId: ClientId): SystemClient? =
        SystemClientTable
            .selectAll()
            .where { SystemClientTable.clientId eq clientId.value }
            .singleOrNull()
            ?.toSystemClient()

    override fun findAll(): List<SystemClient> =
        SystemClientTable
            .selectAll()
            .orderBy(SystemClientTable.createdAt to SortOrder.DESC, SystemClientTable.principalId to SortOrder.DESC)
            .map { it.toSystemClient() }

    override fun createSystemClient(
        clientId: ClientId,
        name: String,
        secretHash: SecretHash,
        createdAt: Instant,
    ): SystemClient {
        val id =
            PrincipalTable
                .insertReturning(listOf(PrincipalTable.id)) {
                    it[PrincipalTable.type] = PrincipalType.SYSTEM.name
                    it[PrincipalTable.status] = AccountStatus.ACTIVE.name
                    it[PrincipalTable.createdAt] = createdAt
                    it[PrincipalTable.updatedAt] = createdAt
                }.single()[PrincipalTable.id]
        val inserted =
            SystemClientTable
                .insertReturning(listOf(SystemClientTable.principalId), ignoreErrors = true) {
                    it[SystemClientTable.principalId] = id
                    it[SystemClientTable.clientId] = clientId.value
                    it[SystemClientTable.clientSecretHash] = secretHash.hex
                    it[SystemClientTable.name] = name
                    it[SystemClientTable.secretRotatedAt] = createdAt
                    it[SystemClientTable.createdAt] = createdAt
                }.any()
        if (!inserted) {
            // 방금 만든 principal은 아직 아무도 참조하지 않으므로 지워서 client 없는 system principal을 남기지 않는다
            PrincipalTable.deleteWhere { PrincipalTable.id eq id }
            throw ClientIdDuplicatedException()
        }
        return SystemClient(id, clientId.value, secretHash, name, createdAt, createdAt)
    }

    override fun rotateSecret(
        principalId: UUID,
        secretHash: SecretHash,
        rotatedAt: Instant,
    ): Boolean =
        SystemClientTable.update({ SystemClientTable.principalId eq principalId }) {
            it[SystemClientTable.clientSecretHash] = secretHash.hex
            it[SystemClientTable.secretRotatedAt] = rotatedAt
        } > 0

    override fun scrubSystemClient(principalId: UUID): Boolean =
        SystemClientTable.update({ SystemClientTable.principalId eq principalId }) {
            it[SystemClientTable.clientId] = SystemClient.deactivatedClientId(principalId)
            it[SystemClientTable.clientSecretHash] = null
        } > 0

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
