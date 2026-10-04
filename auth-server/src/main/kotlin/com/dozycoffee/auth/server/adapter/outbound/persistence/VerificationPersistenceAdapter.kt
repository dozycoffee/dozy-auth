package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.application.port.outbound.verification.ConsumeVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.InvalidateVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.Verification
import com.dozycoffee.auth.server.domain.verification.VerificationMethod
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.jetbrains.exposed.v1.core.LessOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * verification을 저장합니다 (docs/data-model.md §3.6, ADR-0012).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 *
 * - **살아 있는 행**: 만료 전(`expires_at > now`), 소비·무효화 전, 시도 횟수가 남은 행입니다. `Verification.isLive`와 같은 조건입니다.
 * - **발급 (VER-03)**: 부분 UNIQUE 인덱스 `(principal_id, purpose) WHERE consumed_at IS NULL AND invalidated_at IS NULL`은
 *   만료됐지만 무효화되지 않은 행도 막으므로, 만료와 관계없이 소비·무효화 전인 행을 먼저 무효화하고 넣습니다. 넣을 때는
 *   `ON CONFLICT DO NOTHING`입니다. 예외로 받으면 PostgreSQL이 트랜잭션 전체를 중단 상태로 만들기 때문입니다. 같은
 *   `(principal, purpose)`를 동시에 발급하면 늦은 쪽의 INSERT는 앞 트랜잭션이 끝날 때까지 기다렸다가 충돌로 아무것도 넣지 않으며,
 *   그러면 커밋된 앞 행을 무효화하고 다시 넣습니다. 나중에 저장한 토큰 하나만 살아 남습니다.
 * - **소비**: 살아 있을 때만 바꾸는 `UPDATE ... WHERE` 한 문장입니다. 동시에 소비하면 늦은 쪽은 앞 트랜잭션이 끝날 때까지
 *   기다렸다가 바뀐 행으로 조건을 다시 평가하므로 하나만 성공합니다.
 * - 해시로 찾는 비교는 DB의 `=`입니다. 요청 값의 해시로 찾는 것이라 원문 비교가 아닙니다.
 * - 빈 `payload`는 `NULL`로 저장하고, `NULL`은 빈 payload로 읽습니다.
 */
@Component
class VerificationPersistenceAdapter :
    IssueVerificationPort,
    LoadVerificationPort,
    ConsumeVerificationPort,
    InvalidateVerificationPort {
    override fun issue(verification: NewVerification): Verification {
        repeat(MAX_ISSUE_ATTEMPTS) {
            invalidateAll(verification.principalId, verification.purpose, verification.createdAt)
            val inserted =
                VerificationTable
                    .insertReturning(ignoreErrors = true) {
                        it[principalId] = verification.principalId
                        it[purpose] = verification.purpose.name
                        it[method] = verification.method.name
                        it[target] = verification.target.value
                        it[tokenHash] = verification.tokenHash.hex
                        it[payload] = verification.payload.ifEmpty { null }
                        it[maxAttempts] = verification.maxAttempts
                        it[expiresAt] = verification.expiresAt
                        it[createdAt] = verification.createdAt
                    }.singleOrNull()
            if (inserted != null) return inserted.toVerification()
        }
        error("verification을 저장하지 못했습니다. 같은 principal과 purpose로 동시에 발급이 계속 충돌합니다")
    }

    override fun findByTokenHash(tokenHash: SecretHash): Verification? =
        VerificationTable
            .selectAll()
            .where { VerificationTable.tokenHash eq tokenHash.hex }
            .singleOrNull()
            ?.toVerification()

    override fun findLive(
        principalId: UUID,
        purpose: VerificationPurpose,
        now: Instant,
    ): Verification? =
        VerificationTable
            .selectAll()
            .where { (VerificationTable.principalId eq principalId) and (VerificationTable.purpose eq purpose.name) and live(now) }
            .singleOrNull()
            ?.toVerification()

    override fun findUnfinished(
        principalId: UUID,
        purpose: VerificationPurpose,
    ): Verification? =
        VerificationTable
            .selectAll()
            .where { (VerificationTable.principalId eq principalId) and (VerificationTable.purpose eq purpose.name) and notFinished() }
            .singleOrNull()
            ?.toVerification()

    override fun findLive(
        purpose: VerificationPurpose,
        now: Instant,
    ): List<Verification> =
        VerificationTable
            .selectAll()
            .where { (VerificationTable.purpose eq purpose.name) and live(now) }
            .orderBy(VerificationTable.id)
            .map { it.toVerification() }

    override fun consume(
        id: Long,
        now: Instant,
    ): Boolean =
        VerificationTable.update({ (VerificationTable.id eq id) and live(now) }) {
            it[consumedAt] = now
        } > 0

    override fun invalidate(
        id: Long,
        now: Instant,
    ): Boolean = invalidateWhere(now) { VerificationTable.id eq id } > 0

    override fun invalidateAll(
        principalId: UUID,
        purpose: VerificationPurpose,
        now: Instant,
    ): Int = invalidateWhere(now) { (VerificationTable.principalId eq principalId) and (VerificationTable.purpose eq purpose.name) }

    override fun invalidateAll(
        principalId: UUID,
        now: Instant,
    ): Int = invalidateWhere(now) { VerificationTable.principalId eq principalId }

    /** 소비·무효화 전인 행만 무효화합니다. 만료 여부는 보지 않습니다. */
    private fun invalidateWhere(
        now: Instant,
        condition: () -> Op<Boolean>,
    ): Int =
        VerificationTable.update({ condition() and notFinished() }) {
            it[invalidatedAt] = now
        }

    private fun notFinished(): Op<Boolean> = VerificationTable.consumedAt.isNull() and VerificationTable.invalidatedAt.isNull()

    private fun live(now: Instant): Op<Boolean> =
        notFinished() and
            (VerificationTable.expiresAt greater now) and
            (VerificationTable.maxAttempts.isNull() or LessOp(VerificationTable.attemptCount, VerificationTable.maxAttempts))

    private fun ResultRow.toVerification() =
        Verification(
            id = this[VerificationTable.id],
            principalId = this[VerificationTable.principalId],
            purpose = VerificationPurpose.valueOf(this[VerificationTable.purpose]),
            method = VerificationMethod.valueOf(this[VerificationTable.method]),
            target = Email(this[VerificationTable.target]),
            tokenHash = SecretHash(this[VerificationTable.tokenHash]),
            payload =
                this[VerificationTable.payload].orEmpty().mapValues { (key, value) ->
                    value as? String ?: error("verification payload 값은 문자열이어야 합니다: $key")
                },
            attemptCount = this[VerificationTable.attemptCount],
            maxAttempts = this[VerificationTable.maxAttempts],
            expiresAt = this[VerificationTable.expiresAt],
            consumedAt = this[VerificationTable.consumedAt],
            invalidatedAt = this[VerificationTable.invalidatedAt],
            createdAt = this[VerificationTable.createdAt],
        )

    private companion object {
        /** 동시 발급과 충돌했을 때 무효화하고 다시 넣는 횟수의 상한. */
        const val MAX_ISSUE_ATTEMPTS = 10
    }
}
