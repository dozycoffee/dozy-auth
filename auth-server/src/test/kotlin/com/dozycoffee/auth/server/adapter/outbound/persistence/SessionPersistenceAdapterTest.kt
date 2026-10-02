package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.session.NewRefreshSession
import com.dozycoffee.auth.server.domain.session.RefreshSession
import com.dozycoffee.auth.server.domain.session.RevokeReason
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import com.dozycoffee.auth.server.support.SessionFixtures.CURRENT_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.LOGIN_AT
import com.dozycoffee.auth.server.support.SessionFixtures.NEXT_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.UNKNOWN_TOKEN_HASH
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * refresh 세션의 저장, 교체, 폐기 (docs/data-model.md §3.10, domain.md SES-01·SES-03·SES-04·SES-06).
 * 만료 기준은 `AuthPolicy`에서 가져옵니다.
 */
@PersistenceAdapterTest
class SessionPersistenceAdapterTest {
    private val adapter = SessionPersistenceAdapter()

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `SES-01 세션을 만들면 DB가 만든 id와 넘긴 값을 저장하고 현재 토큰 해시로 찾음`() {
        val principalId = insertPrincipal()

        val created = startSession(principalId, userAgent = "DozyApp/1.0 (iOS)", ip = "203.0.113.7")

        assertEquals(4, created.id.version())
        assertEquals(
            RefreshSession(
                id = created.id,
                principalId = principalId,
                realm = Realm.INTERNAL,
                currentTokenHash = CURRENT_TOKEN_HASH,
                previousTokenHash = null,
                rotatedAt = null,
                createdAt = LOGIN_AT,
                lastUsedAt = LOGIN_AT,
                expiresAt = LOGIN_AT.plus(AuthPolicy.REFRESH_IDLE_TTL),
                absoluteExpiresAt = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL),
                revokedAt = null,
                revokeReason = null,
                userAgent = "DozyApp/1.0 (iOS)",
                ip = "203.0.113.7",
            ),
            created,
        )
        assertEquals(created, adapter.findSessionByTokenHash(CURRENT_TOKEN_HASH))
    }

    @Test
    fun `inet 컬럼의 IPv6 주소와 비어 있는 접속 정보를 그대로 돌려줌`() {
        val principalId = insertPrincipal()
        val ipv6 = startSession(principalId, tokenHash = SecretHash.of("ipv6"), ip = "2001:db8::1")
        val unknown = startSession(principalId, tokenHash = SecretHash.of("unknown-client"), userAgent = null, ip = null)

        assertEquals("2001:db8::1", adapter.findSessionByTokenHash(SecretHash.of("ipv6"))?.ip)
        assertEquals(ipv6, adapter.findSessionByTokenHash(SecretHash.of("ipv6")))
        assertEquals(unknown, adapter.findSessionByTokenHash(SecretHash.of("unknown-client")))
        assertNull(unknown.userAgent)
        assertNull(unknown.ip)
    }

    @Test
    fun `일치하는 토큰 해시가 없으면 null`() {
        startSession(insertPrincipal())

        assertNull(adapter.findSessionByTokenHash(UNKNOWN_TOKEN_HASH))
    }

    @Test
    fun `SES-03 현재 토큰이면 교체하고 만료를 교체 시각에서 유휴 만료 시간만큼 연장`() {
        val created = startSession(insertPrincipal())
        val now = LOGIN_AT.plus(Duration.ofHours(1))

        val rotated = adapter.rotate(CURRENT_TOKEN_HASH, NEXT_TOKEN_HASH, now)

        assertEquals(
            created.copy(
                currentTokenHash = NEXT_TOKEN_HASH,
                previousTokenHash = CURRENT_TOKEN_HASH,
                rotatedAt = now,
                lastUsedAt = now,
                expiresAt = now.plus(AuthPolicy.REFRESH_IDLE_TTL),
            ),
            rotated,
        )
        assertEquals(rotated, adapter.findSessionByTokenHash(NEXT_TOKEN_HASH))
    }

    @Test
    fun `SES-03 교체한 뒤에는 직전 토큰 해시로도 같은 세션을 찾음`() {
        startSession(insertPrincipal())
        val rotated = adapter.rotate(CURRENT_TOKEN_HASH, NEXT_TOKEN_HASH, LOGIN_AT.plusSeconds(60))

        assertEquals(rotated, adapter.findSessionByTokenHash(CURRENT_TOKEN_HASH))
    }

    @Test
    fun `SES-03 교체해도 만료는 절대 만료를 넘지 않음`() {
        val created = startSession(insertPrincipal())
        val absolute = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL)
        // 갱신을 이어 와서 만료가 절대 만료까지 늘어난 세션
        RefreshSessionTable.update({ RefreshSessionTable.id eq created.id }) { it[expiresAt] = absolute }

        val rotated = adapter.rotate(CURRENT_TOKEN_HASH, NEXT_TOKEN_HASH, absolute.minus(Duration.ofHours(1)))

        assertEquals(absolute, rotated?.expiresAt)
    }

    @Test
    fun `SES-04 직전 토큰으로는 교체하지 않음`() {
        startSession(insertPrincipal())
        val first = adapter.rotate(CURRENT_TOKEN_HASH, NEXT_TOKEN_HASH, LOGIN_AT.plusSeconds(60))

        assertNull(adapter.rotate(CURRENT_TOKEN_HASH, SecretHash.of("other"), LOGIN_AT.plusSeconds(61)))
        assertEquals(first, adapter.findSessionByTokenHash(NEXT_TOKEN_HASH))
    }

    @Test
    fun `SES-04 만료 시각이 된 세션은 교체하지 않음`() {
        val created = startSession(insertPrincipal())

        assertNull(adapter.rotate(CURRENT_TOKEN_HASH, NEXT_TOKEN_HASH, created.expiresAt))
        assertEquals(created, adapter.findSessionByTokenHash(CURRENT_TOKEN_HASH))
    }

    @Test
    fun `SES-04 폐기된 세션은 교체하지 않음`() {
        val created = startSession(insertPrincipal())
        adapter.revokeSession(created.id, RevokeReason.LOGOUT, LOGIN_AT.plusSeconds(60))

        assertNull(adapter.rotate(CURRENT_TOKEN_HASH, NEXT_TOKEN_HASH, LOGIN_AT.plusSeconds(61)))
        assertEquals(CURRENT_TOKEN_HASH, adapter.findSessionByTokenHash(CURRENT_TOKEN_HASH)?.currentTokenHash)
    }

    @Test
    fun `SES-04 모르는 토큰으로는 교체하지 않음`() {
        startSession(insertPrincipal())

        assertNull(adapter.rotate(UNKNOWN_TOKEN_HASH, NEXT_TOKEN_HASH, LOGIN_AT.plusSeconds(60)))
    }

    @Test
    fun `SES-06 세션 하나를 폐기하면 폐기 시각과 사유를 남김`() {
        val created = startSession(insertPrincipal())
        val now = LOGIN_AT.plusSeconds(60)

        assertTrue(adapter.revokeSession(created.id, RevokeReason.LOGOUT, now))

        assertEquals(created.copy(revokedAt = now, revokeReason = RevokeReason.LOGOUT), adapter.findSessionByTokenHash(CURRENT_TOKEN_HASH))
    }

    @Test
    fun `SES-06 이미 폐기된 세션은 다시 폐기하지 않고 처음 폐기 기록을 유지`() {
        val created = startSession(insertPrincipal())
        adapter.revokeSession(created.id, RevokeReason.REUSE_DETECTED, LOGIN_AT.plusSeconds(60))

        assertFalse(adapter.revokeSession(created.id, RevokeReason.LOGOUT, LOGIN_AT.plusSeconds(120)))

        val stored = adapter.findSessionByTokenHash(CURRENT_TOKEN_HASH)
        assertEquals(LOGIN_AT.plusSeconds(60), stored?.revokedAt)
        assertEquals(RevokeReason.REUSE_DETECTED, stored?.revokeReason)
    }

    @Test
    fun `SES-08 없는 세션이나 만료된 세션은 폐기하지 않고 false`() {
        val created = startSession(insertPrincipal())

        assertFalse(adapter.revokeSession(UUID.randomUUID(), RevokeReason.LOGOUT, LOGIN_AT))
        assertFalse(adapter.revokeSession(created.id, RevokeReason.LOGOUT, created.expiresAt))
        assertNull(adapter.findSessionByTokenHash(CURRENT_TOKEN_HASH)?.revokedAt)
    }

    @Test
    fun `PWD-07 principal의 살아 있는 세션을 모두 폐기하고 폐기한 수를 돌려줌`() {
        val principalId = insertPrincipal()
        val other = insertPrincipal()
        val now = LOGIN_AT.plus(Duration.ofHours(1))
        val first = startSession(principalId, tokenHash = SecretHash.of("first"))
        val second = startSession(principalId, tokenHash = SecretHash.of("second"))
        val alreadyRevoked = startSession(principalId, tokenHash = SecretHash.of("revoked"))
        adapter.revokeSession(alreadyRevoked.id, RevokeReason.LOGOUT, LOGIN_AT.plusSeconds(60))
        val expired = startSession(principalId, tokenHash = SecretHash.of("expired"), loginAt = now.minus(AuthPolicy.REFRESH_IDLE_TTL))
        val othersSession = startSession(other, tokenHash = SecretHash.of("other"))

        assertEquals(2, adapter.revokeAllSessions(principalId, RevokeReason.PASSWORD_RESET, now))

        assertEquals(first.copy(revokedAt = now, revokeReason = RevokeReason.PASSWORD_RESET), find("first"))
        assertEquals(second.copy(revokedAt = now, revokeReason = RevokeReason.PASSWORD_RESET), find("second"))
        assertEquals(alreadyRevoked.copy(revokedAt = LOGIN_AT.plusSeconds(60), revokeReason = RevokeReason.LOGOUT), find("revoked"))
        assertEquals(expired, find("expired"))
        assertEquals(othersSession, find("other"))
    }

    @Test
    fun `PWD-06 principal의 세션 중 현재 세션만 남기고 폐기한 수를 돌려줌`() {
        val principalId = insertPrincipal()
        val now = LOGIN_AT.plus(Duration.ofHours(1))
        val current = startSession(principalId, tokenHash = SecretHash.of("current"))
        val first = startSession(principalId, tokenHash = SecretHash.of("first"))
        val second = startSession(principalId, tokenHash = SecretHash.of("second"))

        assertEquals(2, adapter.revokeAllSessionsExcept(principalId, current.id, RevokeReason.PASSWORD_CHANGED, now))

        assertEquals(current, find("current"))
        assertEquals(first.copy(revokedAt = now, revokeReason = RevokeReason.PASSWORD_CHANGED), find("first"))
        assertEquals(second.copy(revokedAt = now, revokeReason = RevokeReason.PASSWORD_CHANGED), find("second"))
    }

    @Test
    fun `폐기할 세션이 없으면 0`() {
        val principalId = insertPrincipal()

        assertEquals(0, adapter.revokeAllSessions(principalId, RevokeReason.ACCOUNT_SUSPENDED, LOGIN_AT))
        assertEquals(0, adapter.revokeAllSessionsExcept(principalId, UUID.randomUUID(), RevokeReason.PASSWORD_CHANGED, LOGIN_AT))
    }

    @Test
    fun `SES-06 모든 폐기 사유를 저장하고 다시 읽음`() {
        val principalId = insertPrincipal()

        RevokeReason.entries.forEach { reason ->
            val session = startSession(principalId, tokenHash = SecretHash.of("reason-$reason"))
            adapter.revokeSession(session.id, reason, LOGIN_AT)

            assertEquals(reason, find("reason-$reason")?.revokeReason)
        }
    }

    /**
     * 커밋된 세션을 같은 토큰으로 두 트랜잭션이 동시에 교체하면 하나만 교체합니다.
     * 테스트 트랜잭션 밖에서 실행하고 만든 행은 직접 지웁니다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `SES-04 같은 토큰으로 동시에 교체하면 하나만 교체`() {
        val tx = TransactionTemplate(transactionManager)
        val presented = SecretHash.of("concurrent-${UUID.randomUUID()}")
        val newHashes = listOf(SecretHash.of("concurrent-a-${UUID.randomUUID()}"), SecretHash.of("concurrent-b-${UUID.randomUUID()}"))
        val principalId = checkNotNull(tx.execute { insertPrincipal() })
        val executor = Executors.newFixedThreadPool(newHashes.size)
        try {
            tx.executeWithoutResult { startSession(principalId, tokenHash = presented) }
            val start = CountDownLatch(1)
            val futures =
                newHashes.map { newHash ->
                    executor.submit<RefreshSession?> {
                        start.await()
                        tx.execute { adapter.rotate(presented, newHash, LOGIN_AT.plusSeconds(60)) }
                    }
                }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            val winner = results.filterNotNull().single()
            val stored = tx.execute { adapter.findSessionByTokenHash(presented) }
            assertEquals(winner, stored)
            assertEquals(presented, stored?.previousTokenHash)
            assertTrue(newHashes.contains(stored?.currentTokenHash))
        } finally {
            executor.shutdownNow()
            tx.executeWithoutResult {
                RefreshSessionTable.deleteWhere { RefreshSessionTable.principalId eq principalId }
                PrincipalTable.deleteWhere { PrincipalTable.id eq principalId }
            }
        }
    }

    private fun startSession(
        principalId: UUID,
        tokenHash: SecretHash = CURRENT_TOKEN_HASH,
        loginAt: Instant = LOGIN_AT,
        userAgent: String? = "DozyApp/1.0",
        ip: String? = "203.0.113.7",
    ): RefreshSession = adapter.createSession(NewRefreshSession.start(principalId, Realm.INTERNAL, tokenHash, userAgent, ip, loginAt))

    private fun find(token: String): RefreshSession? = adapter.findSessionByTokenHash(SecretHash.of(token))

    private fun insertPrincipal(): UUID =
        PrincipalTable
            .insertReturning(listOf(PrincipalTable.id)) {
                it[type] = "EMPLOYEE"
                it[status] = "ACTIVE"
            }.single()[PrincipalTable.id]
}
