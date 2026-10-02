package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.application.port.outbound.credential.LoadPasswordCredentialPort
import com.dozycoffee.auth.server.domain.credential.PasswordHash
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 비밀번호 credential을 저장합니다 (docs/data-model.md §3.5).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 */
@Component
class CredentialPersistenceAdapter : LoadPasswordCredentialPort {
    override fun findPasswordHash(principalId: UUID): PasswordHash? =
        PasswordCredentialTable
            .select(PasswordCredentialTable.passwordHash)
            .where { PasswordCredentialTable.principalId eq principalId }
            .singleOrNull()
            ?.let { PasswordHash(it[PasswordCredentialTable.passwordHash]) }
}
