package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.application.port.outbound.authorization.LockOwnerBootstrapPort
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.stereotype.Component

/**
 * PostgreSQL 트랜잭션 단위 advisory lock(`pg_advisory_xact_lock`)으로 구현한 잠금입니다.
 *
 * 잠금은 호출한 UseCase의 트랜잭션이 끝나면 PostgreSQL이 풀어 주므로 따로 풀지 않습니다. 트랜잭션은 열지 않으며,
 * 트랜잭션 밖에서 부르면 Exposed가 예외를 던집니다 (architecture.md §9). Exposed DSL에 이 함수가 없어 SQL을 직접 씁니다.
 */
@Component
class AdvisoryLockPersistenceAdapter : LockOwnerBootstrapPort {
    override fun lockOwnerBootstrap() {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(?)", listOf(LongColumnType() to OWNER_BOOTSTRAP_KEY))
    }

    private companion object {
        /** advisory lock 키. 용도마다 고정한 값을 씁니다 ("dozy" 다음에 용도 번호 1). */
        const val OWNER_BOOTSTRAP_KEY: Long = 0x646F_7A79_0000_0001
    }
}
