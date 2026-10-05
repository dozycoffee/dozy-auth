package com.dozycoffee.auth.server.application.service.system

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.metrics.MetricsMicrometerAdapter
import com.dozycoffee.auth.server.application.port.inbound.system.CleanupResult
import com.dozycoffee.auth.server.application.service.system.CleanUpExpiredDataService.Companion.BATCH_SIZE
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.support.CleanupRows
import com.dozycoffee.auth.server.support.CleanupRows.MICROSECOND
import com.dozycoffee.auth.server.support.TestEmployees
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUD-05 정리 배치를 실제 DB에서 끝까지 실행합니다 (data-model.md §5).
 *
 * 묶음마다 커밋하는지 봐야 하므로 `@PersistenceAdapterTest`(테스트마다 롤백)를 쓰지 않습니다. 서비스는 트랜잭션을 열지 않으므로,
 * 묶음 삭제가 `CleanupBatch`의 트랜잭션 없이 실행되면 Exposed가 실패합니다. 넣은 행은 끝나면 지웁니다.
 *
 * 스프링 컨텍스트를 새로 만들면 테스트 DB 연결 수가 한도를 넘으므로, API 테스트(`LoginApiTest` 등)와 같은 설정으로 그 컨텍스트를
 * 함께 씁니다. `@AutoConfigureMockMvc`와 `TestEmployees`는 이 때문에만 둡니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
class CleanUpExpiredDataIntegrationTest {
    @Autowired
    private lateinit var batch: CleanupBatch

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val principals = mutableListOf<UUID>()
    private val auditLogIds = mutableListOf<Long>()

    @AfterEach
    fun cleanUp() {
        inTransaction { CleanupRows.delete(principals, auditLogIds) }
    }

    @Test
    fun `AUD-05 보관 기간이 지난 행을 묶음 여러 개에 걸쳐 모두 지우고 나머지는 남김`() {
        val sessionCutoff = NOW.minus(AuthPolicy.SESSION_RETENTION)
        val verificationCutoff = NOW.minus(AuthPolicy.VERIFICATION_RETENTION)
        val auditCutoff = NOW.minus(AuthPolicy.AUDIT_RETENTION)
        val rows =
            inTransaction {
                val principal = CleanupRows.insertPrincipal().also { principals += it }
                Rows(
                    // 묶음 크기를 넘게 넣어 두 번 이상 지우게 합니다
                    endedSessions = CleanupRows.insertSessions(principal, absoluteExpiresAt = sessionCutoff, count = BATCH_SIZE + 1),
                    revokedSession =
                        CleanupRows
                            .insertSessions(principal, absoluteExpiresAt = NOW.plus(Duration.ofDays(1)), revokedAt = sessionCutoff)
                            .single(),
                    recentlyEndedSession =
                        CleanupRows.insertSessions(principal, absoluteExpiresAt = sessionCutoff.plus(MICROSECOND)).single(),
                    liveSession = CleanupRows.insertSessions(principal, absoluteExpiresAt = NOW.plus(Duration.ofDays(1))).single(),
                    expiredVerification = verification(expiresAt = verificationCutoff),
                    consumedVerification = verification(expiresAt = NOW.plus(Duration.ofHours(1)), consumedAt = verificationCutoff),
                    recentlyExpiredVerification = verification(expiresAt = verificationCutoff.plus(MICROSECOND)),
                    liveVerification = verification(expiresAt = NOW.plus(Duration.ofHours(1))),
                    oldAuditLogs = CleanupRows.insertAuditLogs(auditCutoff, count = 2 * BATCH_SIZE + 1),
                    recentAuditLog = CleanupRows.insertAuditLogs(auditCutoff.plus(MICROSECOND)).single(),
                ).also { auditLogIds += it.oldAuditLogs + it.recentAuditLog }
            }

        val result = service().cleanUp()

        assertEquals(CleanupResult(sessions = BATCH_SIZE + 2L, verifications = 2, auditLogs = 2L * BATCH_SIZE + 1), result)
        inTransaction {
            assertEquals(
                setOf(rows.recentlyEndedSession, rows.liveSession),
                CleanupRows.remainingSessions(rows.endedSessions + rows.revokedSession + rows.recentlyEndedSession + rows.liveSession),
            )
            assertEquals(
                setOf(rows.recentlyExpiredVerification, rows.liveVerification),
                CleanupRows.remainingVerifications(
                    listOf(
                        rows.expiredVerification,
                        rows.consumedVerification,
                        rows.recentlyExpiredVerification,
                        rows.liveVerification,
                    ),
                ),
            )
            assertEquals(setOf(rows.recentAuditLog), CleanupRows.remainingAuditLogs(rows.oldAuditLogs + rows.recentAuditLog))
        }
    }

    @Test
    fun `AUD-05 principal은 지우지 않음`() {
        val principal = inTransaction { CleanupRows.insertPrincipal().also { principals += it } }
        inTransaction { CleanupRows.insertSessions(principal, absoluteExpiresAt = NOW.minus(AuthPolicy.SESSION_RETENTION)) }

        service().cleanUp()

        assertTrue(inTransaction { CleanupRows.principalExists(principal) })
    }

    /** 소비·무효화 전인 행이 (principal, purpose)마다 하나라서(VER-03) principal을 따로 만듭니다. */
    private fun verification(
        expiresAt: Instant,
        consumedAt: Instant? = null,
    ): Long {
        val principal = CleanupRows.insertPrincipal().also { principals += it }
        return CleanupRows.insertVerification(principal, expiresAt = expiresAt, consumedAt = consumedAt)
    }

    private fun service(): CleanUpExpiredDataService {
        val metrics = MetricsMicrometerAdapter(SimpleMeterRegistry())
        return CleanUpExpiredDataService(batch, metrics, Clock.fixed(NOW, ZoneOffset.UTC))
    }

    private fun <T> inTransaction(block: () -> T): T = checkNotNull(TransactionTemplate(transactionManager).execute { block() })

    private data class Rows(
        val endedSessions: List<UUID>,
        val revokedSession: UUID,
        val recentlyEndedSession: UUID,
        val liveSession: UUID,
        val expiredVerification: Long,
        val consumedVerification: Long,
        val recentlyExpiredVerification: Long,
        val liveVerification: Long,
        val oldAuditLogs: List<Long>,
        val recentAuditLog: Long,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-05T19:00:00Z")
    }
}
