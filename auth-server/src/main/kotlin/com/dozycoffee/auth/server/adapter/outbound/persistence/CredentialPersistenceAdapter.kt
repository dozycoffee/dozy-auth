package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PasswordCredentialTable
import com.dozycoffee.auth.server.application.port.outbound.credential.CreatePasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.credential.DeletePasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.credential.LoadPasswordCredentialPort
import com.dozycoffee.auth.server.domain.credential.PasswordHash
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * 비밀번호 credential을 저장합니다 (docs/data-model.md §3.5).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 * 시각은 DB의 `now()`가 아니라 호출한 쪽이 넘긴 `Clock` 시각입니다.
 */
@Component
class CredentialPersistenceAdapter :
    LoadPasswordCredentialPort,
    CreatePasswordCredentialPort,
    DeletePasswordCredentialPort {
    override fun findPasswordHash(principalId: UUID): PasswordHash? =
        PasswordCredentialTable
            .select(PasswordCredentialTable.passwordHash)
            .where { PasswordCredentialTable.principalId eq principalId }
            .singleOrNull()
            ?.let { PasswordHash(it[PasswordCredentialTable.passwordHash]) }

    override fun createPasswordCredential(
        principalId: UUID,
        hash: PasswordHash,
        createdAt: Instant,
    ) {
        PasswordCredentialTable.insert {
            it[PasswordCredentialTable.principalId] = principalId
            it[passwordHash] = hash.encoded
            it[changedAt] = createdAt
            it[PasswordCredentialTable.createdAt] = createdAt
        }
    }

    override fun deletePasswordCredential(principalId: UUID): Boolean =
        PasswordCredentialTable.deleteWhere { PasswordCredentialTable.principalId eq principalId } > 0
}
