package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** GOV-11 부트스트랩 잠금은 트랜잭션 단위 advisory lock이라 트랜잭션이 끝나면 풀림. */
@PersistenceAdapterTest
class AdvisoryLockPersistenceAdapterTest {
    private val adapter = AdvisoryLockPersistenceAdapter()

    @Test
    fun `부트스트랩 잠금을 얻으면 트랜잭션 단위 advisory lock을 쥠`() {
        adapter.lockOwnerBootstrap()

        assertEquals(1, transactionAdvisoryLocks())
    }

    @Test
    fun `같은 트랜잭션에서 다시 얻어도 기다리지 않음`() {
        adapter.lockOwnerBootstrap()
        adapter.lockOwnerBootstrap()

        assertEquals(1, transactionAdvisoryLocks())
    }

    /** 이 연결이 쥔 advisory lock 종류 수. */
    private fun transactionAdvisoryLocks(): Int =
        checkNotNull(
            TransactionManager.current().exec(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()",
            ) { rs -> rs.next().let { rs.getInt(1) } },
        )
}
