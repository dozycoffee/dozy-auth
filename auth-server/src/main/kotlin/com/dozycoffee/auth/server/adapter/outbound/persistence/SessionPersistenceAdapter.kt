package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RefreshSessionTable
import com.dozycoffee.auth.server.application.port.outbound.session.CreateRefreshSessionPort
import com.dozycoffee.auth.server.application.port.outbound.session.LoadRefreshSessionPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.session.RotateRefreshSessionPort
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.session.NewRefreshSession
import com.dozycoffee.auth.server.domain.session.RefreshSession
import com.dozycoffee.auth.server.domain.session.RevokeReason
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * refresh 세션을 저장합니다 (docs/data-model.md §3.10).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 *
 * - 교체는 data-model.md §3.10의 갱신 쿼리 한 문장(`UPDATE ... WHERE current_token_hash = ? AND revoked_at IS NULL
 *   AND expires_at > ? RETURNING ...`)입니다. 같은 토큰으로 동시에 교체하면 뒤 요청은 앞 요청이 커밋할 때까지 기다렸다가
 *   바뀐 행에 조건을 다시 적용하므로 0행이 됩니다 (SES-04).
 * - 만료 연장은 `least(:now + idle-ttl, absolute_expires_at)`입니다. `:now + idle-ttl`은 `RefreshSession.idleExpiresAt`으로
 *   애플리케이션에서 계산해 넘깁니다.
 * - 시각은 모두 DB의 `now()`가 아니라 호출한 쪽이 넘긴 `Clock` 시각입니다. `created_at`도 절대 만료와 같은 기준이 되도록 넘긴 값을 씁니다.
 * - 폐기는 살아 있는(폐기되지 않고 만료 전인) 세션만 바꿉니다.
 */
@Component
class SessionPersistenceAdapter :
    CreateRefreshSessionPort,
    LoadRefreshSessionPort,
    RotateRefreshSessionPort,
    RevokeSessionsPort {
    override fun createSession(session: NewRefreshSession): RefreshSession =
        RefreshSessionTable
            .insertReturning {
                it[principalId] = session.principalId
                it[realm] = session.realm.name
                it[currentTokenHash] = session.tokenHash.hex
                it[createdAt] = session.createdAt
                it[lastUsedAt] = session.createdAt
                it[expiresAt] = session.expiresAt
                it[absoluteExpiresAt] = session.absoluteExpiresAt
                it[userAgent] = session.userAgent
                it[ip] = session.ip
            }.single()
            .toRefreshSession()

    override fun findSessionByTokenHash(tokenHash: SecretHash): RefreshSession? =
        RefreshSessionTable
            .selectAll()
            .where {
                (RefreshSessionTable.currentTokenHash eq tokenHash.hex) or (RefreshSessionTable.previousTokenHash eq tokenHash.hex)
            }.limit(1)
            .singleOrNull()
            ?.toRefreshSession()

    override fun rotate(
        presentedHash: SecretHash,
        newHash: SecretHash,
        now: Instant,
    ): RefreshSession? =
        RefreshSessionTable
            .updateReturning(
                where = { (RefreshSessionTable.currentTokenHash eq presentedHash.hex) and live(now) },
            ) {
                it[previousTokenHash] = currentTokenHash
                it[currentTokenHash] = newHash.hex
                it[rotatedAt] = now
                it[lastUsedAt] = now
                it[expiresAt] =
                    CustomFunction(
                        "LEAST",
                        expiresAt.columnType,
                        QueryParameter(RefreshSession.idleExpiresAt(now), expiresAt.columnType),
                        absoluteExpiresAt,
                    )
            }.singleOrNull()
            ?.toRefreshSession()

    override fun revokeSession(
        sessionId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Boolean = revoke(reason, now) { RefreshSessionTable.id eq sessionId } > 0

    override fun revokeAllSessions(
        principalId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Int = revoke(reason, now) { RefreshSessionTable.principalId eq principalId }

    override fun revokeAllSessionsExcept(
        principalId: UUID,
        keepSessionId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Int = revoke(reason, now) { (RefreshSessionTable.principalId eq principalId) and (RefreshSessionTable.id neq keepSessionId) }

    private fun revoke(
        reason: RevokeReason,
        now: Instant,
        target: () -> Op<Boolean>,
    ): Int =
        RefreshSessionTable.update({ target() and live(now) }) {
            it[revokedAt] = now
            it[revokeReason] = reason.name
        }

    /** 폐기되지 않았고 [now]에 만료 전인 세션 (`RefreshSession.isAlive`와 같은 조건). */
    private fun live(now: Instant): Op<Boolean> = RefreshSessionTable.revokedAt.isNull() and (RefreshSessionTable.expiresAt greater now)

    private fun ResultRow.toRefreshSession() =
        RefreshSession(
            id = this[RefreshSessionTable.id],
            principalId = this[RefreshSessionTable.principalId],
            realm = Realm.valueOf(this[RefreshSessionTable.realm]),
            currentTokenHash = SecretHash(this[RefreshSessionTable.currentTokenHash]),
            previousTokenHash = this[RefreshSessionTable.previousTokenHash]?.let(::SecretHash),
            rotatedAt = this[RefreshSessionTable.rotatedAt],
            createdAt = this[RefreshSessionTable.createdAt],
            lastUsedAt = this[RefreshSessionTable.lastUsedAt],
            expiresAt = this[RefreshSessionTable.expiresAt],
            absoluteExpiresAt = this[RefreshSessionTable.absoluteExpiresAt],
            revokedAt = this[RefreshSessionTable.revokedAt],
            revokeReason = this[RefreshSessionTable.revokeReason]?.let(RevokeReason::valueOf),
            userAgent = this[RefreshSessionTable.userAgent],
            ip = this[RefreshSessionTable.ip],
        )
}
