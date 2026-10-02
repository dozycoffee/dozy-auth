package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.Verification
import com.dozycoffee.auth.server.domain.verification.VerificationMethod
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** verification 저장 (docs/data-model.md §3.6, domain.md §7). 유효 시간은 `AuthPolicy`에서 가져옵니다. */
@PersistenceAdapterTest
class VerificationPersistenceAdapterTest {
    private val adapter = VerificationPersistenceAdapter()

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `발급한 토큰을 해시로 찾으면 저장한 값을 그대로 돌려줌`() {
        val principal = insertPrincipal()
        val issued =
            NewVerification.issue(
                principal,
                VerificationPurpose.EMPLOYEE_INVITATION,
                Email("Kim.Barista@DozyCoffee.com"),
                NOW,
                payload = mapOf("invitedBy" to "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f"),
            )

        val saved = adapter.issue(issued.verification)

        val found = adapter.findByTokenHash(SecretHash.of(issued.token.value))
        assertEquals(saved, found)
        assertNotNull(found)
        assertEquals(principal, found.principalId)
        assertEquals(VerificationPurpose.EMPLOYEE_INVITATION, found.purpose)
        assertEquals(VerificationMethod.EMAIL, found.method)
        assertEquals(Email("Kim.Barista@DozyCoffee.com"), found.target)
        assertEquals(mapOf("invitedBy" to "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f"), found.payload)
        assertEquals(0, found.attemptCount)
        assertNull(found.maxAttempts)
        assertEquals(NOW.plus(AuthPolicy.INVITATION_TTL), found.expiresAt)
        assertEquals(NOW, found.createdAt)
        assertNull(found.consumedAt)
        assertNull(found.invalidatedAt)
    }

    @Test
    fun `VER-02 DB에는 토큰 원문이 아니라 해시만 남김`() {
        val issued = NewVerification.issue(insertPrincipal(), VerificationPurpose.PASSWORD_RESET, TARGET, NOW)

        adapter.issue(issued.verification)

        val row = VerificationTable.selectAll().single()
        assertEquals(SecretHash.of(issued.token.value).hex, row[VerificationTable.tokenHash])
        assertTrue(VerificationTable.selectAll().none { it[VerificationTable.tokenHash] == issued.token.value })
    }

    @Test
    fun `없는 해시는 null`() {
        assertNull(adapter.findByTokenHash(SecretHash.of("unknown")))
    }

    @Test
    fun `payload가 비어 있으면 NULL로 저장하고 빈 payload로 읽음`() {
        val saved = issue(insertPrincipal(), VerificationPurpose.PASSWORD_RESET)

        assertNull(VerificationTable.selectAll().single()[VerificationTable.payload])
        assertEquals(emptyMap(), saved.payload)
    }

    @Test
    fun `VER-03 다시 발급하면 이전 토큰을 새 발급 시각으로 무효화하고 새 토큰만 살아 있음`() {
        val principal = insertPrincipal()
        val first = issue(principal, VerificationPurpose.PASSWORD_RESET, NOW)

        val second = issue(principal, VerificationPurpose.PASSWORD_RESET, LATER)

        assertEquals(LATER, reload(first).invalidatedAt)
        assertFalse(reload(first).isLive(LATER))
        assertNull(reload(second).invalidatedAt)
        assertEquals(second, adapter.findLive(principal, VerificationPurpose.PASSWORD_RESET, LATER))
    }

    @Test
    fun `VER-03 이전 토큰이 만료됐지만 무효화되지 않았어도 다시 발급할 수 있음`() {
        val principal = insertPrincipal()
        val first = issue(principal, VerificationPurpose.PASSWORD_RESET, NOW)
        val afterExpiry = first.expiresAt.plusSeconds(1)

        val second = issue(principal, VerificationPurpose.PASSWORD_RESET, afterExpiry)

        assertEquals(afterExpiry, reload(first).invalidatedAt)
        assertEquals(second, adapter.findLive(principal, VerificationPurpose.PASSWORD_RESET, afterExpiry))
    }

    @Test
    fun `VER-03 다시 발급해도 다른 목적과 다른 principal의 토큰은 그대로`() {
        val principal = insertPrincipal()
        val other = insertPrincipal()
        val invitation = issue(principal, VerificationPurpose.EMPLOYEE_INVITATION, NOW)
        val othersReset = issue(other, VerificationPurpose.PASSWORD_RESET, NOW)
        issue(principal, VerificationPurpose.PASSWORD_RESET, NOW)

        issue(principal, VerificationPurpose.PASSWORD_RESET, LATER)

        assertNull(reload(invitation).invalidatedAt)
        assertNull(reload(othersReset).invalidatedAt)
    }

    @Test
    fun `VER-03 이미 사용한 토큰은 다시 발급해도 사용 기록을 바꾸지 않음`() {
        val principal = insertPrincipal()
        val first = issue(principal, VerificationPurpose.SIGNUP_VERIFICATION, NOW)
        adapter.consume(first.id, NOW)

        issue(principal, VerificationPurpose.SIGNUP_VERIFICATION, LATER)

        assertEquals(NOW, reload(first).consumedAt)
        assertNull(reload(first).invalidatedAt)
    }

    @Test
    fun `살아 있는 토큰은 한 번만 소비함`() {
        val saved = issue(insertPrincipal(), VerificationPurpose.PASSWORD_RESET)

        assertTrue(adapter.consume(saved.id, LATER))
        assertFalse(adapter.consume(saved.id, LATER))

        assertEquals(LATER, reload(saved).consumedAt)
    }

    @Test
    fun `VER-04 만료 시각이 된 토큰은 소비하지 않음`() {
        val saved = issue(insertPrincipal(), VerificationPurpose.PASSWORD_RESET)

        assertFalse(adapter.consume(saved.id, saved.expiresAt))

        assertNull(reload(saved).consumedAt)
        assertTrue(adapter.consume(saved.id, saved.expiresAt.minusMillis(1)))
    }

    @Test
    fun `VER-04 무효화된 토큰은 소비하지 않음`() {
        val saved = issue(insertPrincipal(), VerificationPurpose.PASSWORD_RESET)
        adapter.invalidate(saved.id, NOW)

        assertFalse(adapter.consume(saved.id, LATER))

        assertNull(reload(saved).consumedAt)
    }

    @Test
    fun `시도 횟수를 다 쓴 토큰은 소비하지 않고 살아 있는 토큰으로 찾지 않음`() {
        val principal = insertPrincipal()
        val saved = issue(principal, VerificationPurpose.PASSWORD_RESET)
        VerificationTable.update({ VerificationTable.id eq saved.id }) {
            it[maxAttempts] = 3
            it[attemptCount] = 3
        }

        assertFalse(adapter.consume(saved.id, NOW))
        assertNull(adapter.findLive(principal, VerificationPurpose.PASSWORD_RESET, NOW))
    }

    @Test
    fun `없는 토큰은 소비하지 않음`() {
        assertFalse(adapter.consume(Long.MAX_VALUE, NOW))
    }

    @Test
    fun `토큰 하나를 무효화하면 다시 무효화하거나 사용한 토큰을 무효화해도 바꾸지 않음`() {
        val principal = insertPrincipal()
        val transfer = issue(principal, VerificationPurpose.OWNER_TRANSFER)
        val reset = issue(principal, VerificationPurpose.PASSWORD_RESET)
        adapter.consume(reset.id, NOW)

        assertTrue(adapter.invalidate(transfer.id, NOW))
        assertFalse(adapter.invalidate(transfer.id, LATER))
        assertFalse(adapter.invalidate(reset.id, LATER))

        assertEquals(NOW, reload(transfer).invalidatedAt)
        assertNull(reload(reset).invalidatedAt)
        assertFalse(adapter.invalidate(Long.MAX_VALUE, NOW))
    }

    @Test
    fun `principal의 한 목적 토큰만 무효화함`() {
        val principal = insertPrincipal()
        val reset = issue(principal, VerificationPurpose.PASSWORD_RESET)
        val invitation = issue(principal, VerificationPurpose.EMPLOYEE_INVITATION)

        assertEquals(1, adapter.invalidateAll(principal, VerificationPurpose.PASSWORD_RESET, LATER))

        assertEquals(LATER, reload(reset).invalidatedAt)
        assertNull(reload(invitation).invalidatedAt)
    }

    @Test
    fun `ACC-04 비활성화하면 그 principal의 모든 목적의 토큰을 무효화하고 다른 principal은 그대로`() {
        val principal = insertPrincipal()
        val other = insertPrincipal()
        val invitation = issue(principal, VerificationPurpose.EMPLOYEE_INVITATION)
        val reset = issue(principal, VerificationPurpose.PASSWORD_RESET)
        val consumed = issue(principal, VerificationPurpose.SIGNUP_VERIFICATION)
        adapter.consume(consumed.id, NOW)
        val othersReset = issue(other, VerificationPurpose.PASSWORD_RESET)

        assertEquals(2, adapter.invalidateAll(principal, LATER))

        assertEquals(LATER, reload(invitation).invalidatedAt)
        assertEquals(LATER, reload(reset).invalidatedAt)
        assertNull(reload(consumed).invalidatedAt)
        assertNull(reload(othersReset).invalidatedAt)
        assertTrue(VerificationPurpose.entries.none { adapter.findLive(principal, it, LATER) != null })
    }

    @Test
    fun `GOV-09 살아 있는 owner 양도를 principal과 관계없이 찾고 만료·사용·무효화된 양도는 빼고 찾음`() {
        val live = issue(insertPrincipal(), VerificationPurpose.OWNER_TRANSFER, LATER)
        issue(insertPrincipal(), VerificationPurpose.OWNER_TRANSFER, LATER.minus(AuthPolicy.OWNER_TRANSFER_TTL))
        val consumed = issue(insertPrincipal(), VerificationPurpose.OWNER_TRANSFER, LATER)
        adapter.consume(consumed.id, LATER)
        val invalidated = issue(insertPrincipal(), VerificationPurpose.OWNER_TRANSFER, LATER)
        adapter.invalidate(invalidated.id, LATER)
        issue(insertPrincipal(), VerificationPurpose.EMPLOYEE_INVITATION, LATER)

        assertEquals(listOf(live), adapter.findLive(VerificationPurpose.OWNER_TRANSFER, LATER))
    }

    @Test
    fun `GOV-11 owner 초대가 만료되면 살아 있는 초대로 찾지 않음`() {
        val owner = insertPrincipal()
        val invitation = issue(owner, VerificationPurpose.EMPLOYEE_INVITATION, NOW)

        assertEquals(invitation, adapter.findLive(owner, VerificationPurpose.EMPLOYEE_INVITATION, invitation.expiresAt.minusMillis(1)))
        assertNull(adapter.findLive(owner, VerificationPurpose.EMPLOYEE_INVITATION, invitation.expiresAt))
    }

    @Test
    fun `VER-08 발송 대상 주소는 입력값 그대로 스냅샷으로 남김`() {
        val issued = NewVerification.issue(insertPrincipal(), VerificationPurpose.PASSWORD_RESET, Email("Lee@DozyCoffee.COM"), NOW)

        val saved = adapter.issue(issued.verification)

        assertEquals("Lee@DozyCoffee.COM", VerificationTable.selectAll().single()[VerificationTable.target])
        assertEquals(Email("Lee@DozyCoffee.COM"), reload(saved).target)
    }

    /**
     * 커밋된 토큰을 여러 트랜잭션이 동시에 소비해도 하나만 성공합니다.
     * 테스트 트랜잭션 밖에서 실행하고 만든 행은 직접 지웁니다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시에 소비해도 한 번만 성공함`() {
        val tx = TransactionTemplate(transactionManager)
        val principal = checkNotNull(tx.execute { insertPrincipal() })
        try {
            val saved = checkNotNull(tx.execute { issue(principal, VerificationPurpose.PASSWORD_RESET) })

            val results = concurrently(THREADS) { tx.execute { adapter.consume(saved.id, LATER) } == true }

            assertEquals(1, results.count { it })
            assertEquals(LATER, tx.execute { reload(saved).consumedAt })
        } finally {
            tx.executeWithoutResult { deletePrincipal(principal) }
        }
    }

    /**
     * 같은 principal과 목적으로 동시에 발급해도 모두 저장되고 살아 있는 토큰은 하나입니다 (VER-03).
     * 테스트 트랜잭션 밖에서 실행하고 만든 행은 직접 지웁니다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `VER-03 동시에 발급해도 살아 있는 토큰은 하나`() {
        val tx = TransactionTemplate(transactionManager)
        val principal = checkNotNull(tx.execute { insertPrincipal() })
        try {
            val saved = concurrently(THREADS) { checkNotNull(tx.execute { issue(principal, VerificationPurpose.PASSWORD_RESET) }) }

            assertEquals(THREADS, saved.map { it.id }.toSet().size)
            val rows = checkNotNull(tx.execute { saved.map { reload(it) } })
            assertEquals(1, rows.count { it.isLive(NOW) })
            assertEquals(THREADS - 1, rows.count { it.invalidatedAt != null })
        } finally {
            tx.executeWithoutResult { deletePrincipal(principal) }
        }
    }

    private fun <T> concurrently(
        threads: Int,
        action: () -> T,
    ): List<T> {
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val futures =
                (1..threads).map {
                    executor.submit<T> {
                        start.await()
                        action()
                    }
                }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun issue(
        principal: UUID,
        purpose: VerificationPurpose,
        now: Instant = NOW,
    ): Verification = adapter.issue(NewVerification.issue(principal, purpose, TARGET, now).verification)

    private fun reload(verification: Verification): Verification = checkNotNull(adapter.findByTokenHash(verification.tokenHash))

    private fun insertPrincipal(): UUID =
        PrincipalTable
            .insertReturning(listOf(PrincipalTable.id)) {
                it[type] = "EMPLOYEE"
                it[status] = "PENDING"
            }.single()[PrincipalTable.id]

    private fun deletePrincipal(id: UUID) {
        VerificationTable.deleteWhere { VerificationTable.principalId eq id }
        PrincipalTable.deleteWhere { PrincipalTable.id eq id }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        val LATER: Instant = NOW.plusSeconds(60)
        val TARGET = Email("kim@dozycoffee.com")
        const val THREADS = 5
    }
}
