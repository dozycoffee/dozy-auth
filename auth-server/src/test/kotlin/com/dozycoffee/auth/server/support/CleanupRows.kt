package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RefreshSessionTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.VerificationTable
import com.dozycoffee.auth.server.domain.SecretHash
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 정리 배치(AUD-05) 테스트용 행을 테이블에 바로 넣습니다. 호출한 쪽의 트랜잭션 안에서 실행됩니다.
 *
 * 정리 조건에 쓰는 시각만 인자로 받고, 나머지 값은 조건과 관계없게 채웁니다. 다른 테스트가 남긴 행과 섞이지 않도록
 * 결과는 넣은 행의 id로만 확인합니다.
 */
object CleanupRows {
    /** DB(`timestamptz`)가 저장하는 가장 작은 시간 단위. 경계 바로 앞뒤를 만들 때 씁니다. */
    val MICROSECOND: Duration = Duration.ofNanos(1_000)

    fun insertPrincipal(): UUID =
        PrincipalTable
            .insertReturning(listOf(PrincipalTable.id)) {
                it[type] = "EMPLOYEE"
                it[status] = "ACTIVE"
            }.single()[PrincipalTable.id]

    /** [principalId]의 세션 [count]개를 넣고 id를 돌려줍니다. 최초 로그인과 idle 만료는 절대 만료보다 앞입니다. */
    fun insertSessions(
        principalId: UUID,
        absoluteExpiresAt: Instant,
        revokedAt: Instant? = null,
        count: Int = 1,
    ): List<UUID> {
        val createdAt = absoluteExpiresAt.minus(Duration.ofDays(7))
        val hashes = List(count) { randomHash() }
        // id는 DB가 만들고 batchInsert는 돌려주지 않으므로 토큰 해시로 다시 찾습니다
        RefreshSessionTable.batchInsert(hashes, shouldReturnGeneratedValues = false) { hash ->
            this[RefreshSessionTable.principalId] = principalId
            this[RefreshSessionTable.realm] = "INTERNAL"
            this[RefreshSessionTable.currentTokenHash] = hash
            this[RefreshSessionTable.createdAt] = createdAt
            this[RefreshSessionTable.lastUsedAt] = createdAt
            this[RefreshSessionTable.expiresAt] = createdAt.plus(Duration.ofHours(8))
            this[RefreshSessionTable.absoluteExpiresAt] = absoluteExpiresAt
            this[RefreshSessionTable.revokedAt] = revokedAt
            this[RefreshSessionTable.revokeReason] = revokedAt?.let { "LOGOUT" }
        }
        return RefreshSessionTable
            .select(RefreshSessionTable.id)
            .where { RefreshSessionTable.currentTokenHash inList hashes }
            .map { it[RefreshSessionTable.id] }
    }

    /**
     * [principalId]의 verification 하나를 넣고 id를 돌려줍니다. 소비·무효화 전인 행은 (principal, purpose)마다 하나라서(VER-03)
     * 그런 행을 여러 개 넣을 때는 principal을 나눕니다.
     */
    fun insertVerification(
        principalId: UUID,
        expiresAt: Instant,
        consumedAt: Instant? = null,
        invalidatedAt: Instant? = null,
    ): Long =
        VerificationTable
            .insertReturning(listOf(VerificationTable.id)) {
                it[VerificationTable.principalId] = principalId
                it[purpose] = "PASSWORD_RESET"
                it[method] = "EMAIL"
                it[target] = "cleanup@dozycoffee.test"
                it[tokenHash] = randomHash()
                it[VerificationTable.expiresAt] = expiresAt
                it[VerificationTable.consumedAt] = consumedAt
                it[VerificationTable.invalidatedAt] = invalidatedAt
                it[createdAt] = expiresAt.minus(Duration.ofMinutes(30))
            }.single()[VerificationTable.id]

    /** [occurredAt]에 일어난 감사 로그 [count]개를 넣고 id를 돌려줍니다. */
    fun insertAuditLogs(
        occurredAt: Instant,
        count: Int = 1,
    ): List<Long> =
        AuditLogTable
            .batchInsert(1..count) {
                this[AuditLogTable.occurredAt] = occurredAt
                this[AuditLogTable.action] = "PROFILE_UPDATED"
            }.map { it[AuditLogTable.id] }

    fun principalExists(id: UUID): Boolean = PrincipalTable.selectAll().where { PrincipalTable.id eq id }.count() == 1L

    /** [ids] 중 남아 있는 세션. */
    fun remainingSessions(ids: Collection<UUID>): Set<UUID> =
        RefreshSessionTable
            .selectAll()
            .where { RefreshSessionTable.id inList ids }
            .map { it[RefreshSessionTable.id] }
            .toSet()

    /** [ids] 중 남아 있는 verification. */
    fun remainingVerifications(ids: Collection<Long>): Set<Long> =
        VerificationTable
            .selectAll()
            .where { VerificationTable.id inList ids }
            .map { it[VerificationTable.id] }
            .toSet()

    /** [ids] 중 남아 있는 감사 로그. */
    fun remainingAuditLogs(ids: Collection<Long>): Set<Long> =
        AuditLogTable
            .selectAll()
            .where { AuditLogTable.id inList ids }
            .map { it[AuditLogTable.id] }
            .toSet()

    /** 커밋하는 테스트가 넣은 행을 지웁니다. principal의 세션과 verification도 함께 지웁니다. */
    fun delete(
        principalIds: Collection<UUID>,
        auditLogIds: Collection<Long>,
    ) {
        if (auditLogIds.isNotEmpty()) AuditLogTable.deleteWhere { AuditLogTable.id inList auditLogIds }
        if (principalIds.isEmpty()) return
        RefreshSessionTable.deleteWhere { RefreshSessionTable.principalId inList principalIds }
        VerificationTable.deleteWhere { VerificationTable.principalId inList principalIds }
        PrincipalTable.deleteWhere { PrincipalTable.id inList principalIds }
    }

    private fun randomHash(): String = SecretHash.of(UUID.randomUUID().toString()).hex
}
