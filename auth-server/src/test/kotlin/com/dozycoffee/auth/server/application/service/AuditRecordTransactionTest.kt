package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditPersistenceAdapter
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.credential.InvalidCredentialsException
import com.dozycoffee.auth.server.support.PersistenceTestConfiguration
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Service
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 에러 응답으로 끝나도 남아야 하는 감사 기록의 트랜잭션 규칙 (architecture.md §9).
 *
 * 실제로 커밋되는지 봐야 하므로 `@PersistenceAdapterTest`(테스트마다 롤백)를 쓰지 않고, 앞뒤로 `audit_log`를 비웁니다.
 */
@SpringBootTest(classes = [PersistenceTestConfiguration::class])
@Import(AuditRecordTransactionTest.FailingLoginService::class, AuditPersistenceAdapter::class)
@ActiveProfiles("test")
class AuditRecordTransactionTest {
    @Autowired
    private lateinit var service: FailingLoginService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    @AfterEach
    fun clear() {
        inTransaction { AuditLogTable.deleteAll() }
    }

    @Test
    fun `noRollbackFor로 지정한 예외로 끝나면 감사 기록이 커밋됨`() {
        assertFailsWith<InvalidCredentialsException> { service.failWithInvalidCredentials() }

        assertEquals(listOf("LOGIN_FAILED"), inTransaction { AuditLogTable.selectAll().map { it[AuditLogTable.action] } })
    }

    @Test
    fun `지정하지 않은 예외로 끝나면 감사 기록도 롤백됨`() {
        assertFailsWith<IllegalStateException> { service.failUnexpectedly() }

        assertEquals(0L, inTransaction { AuditLogTable.selectAll().count() })
    }

    private fun <T> inTransaction(block: () -> T): T = checkNotNull(TransactionTemplate(transactionManager).execute { block() })

    /** 로그인 실패 경로를 흉내 낸 UseCase 구현. 기록을 마친 뒤 마지막에 예외를 던집니다. */
    @Service
    class FailingLoginService(
        private val recordAuditLogPort: RecordAuditLogPort,
    ) {
        @Transactional(noRollbackFor = [InvalidCredentialsException::class])
        fun failWithInvalidCredentials() {
            recordAuditLogPort.record(AuditEvent.loginFailedForUnknownAccount(NOW, Realm.INTERNAL, null, null))
            throw InvalidCredentialsException()
        }

        @Transactional(noRollbackFor = [InvalidCredentialsException::class])
        fun failUnexpectedly() {
            recordAuditLogPort.record(AuditEvent.loginFailedForUnknownAccount(NOW, Realm.INTERNAL, null, null))
            error("예상하지 못한 오류")
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
