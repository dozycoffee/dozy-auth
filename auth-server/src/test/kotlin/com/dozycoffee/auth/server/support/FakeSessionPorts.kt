package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.server.application.port.outbound.session.LoadRefreshSessionPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.session.RotateRefreshSessionPort
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.session.RefreshSession
import com.dozycoffee.auth.server.domain.session.RevokeReason
import java.time.Instant
import java.util.UUID

/**
 * 세션 포트(조회, 교체, 폐기)의 테스트 대역. 받은 호출을 기록하고 정해 둔 값을 돌려줍니다.
 *
 * `SecretHash`는 형식을 검사하는 값 클래스라 MockK의 `any()`가 만드는 임의 값이 검사를 통과하지 못합니다. 그래서 MockK 대신
 * 직접 구현합니다.
 */
class FakeSessionPorts :
    LoadRefreshSessionPort,
    RotateRefreshSessionPort,
    RevokeSessionsPort {
    /** 조회할 때마다 차례로 돌려줄 세션. 다 쓰면 마지막 값을 계속 돌려줍니다. */
    var found: List<RefreshSession?> = listOf(null)

    /** 교체 결과. `null`이면 교체하지 않은 것(0행)입니다. */
    var rotated: RefreshSession? = null

    /** 폐기 결과. `false`면 살아 있는 세션이 없어 폐기하지 않은 것입니다. */
    var revoked: Boolean = true

    val lookups = mutableListOf<SecretHash>()
    val rotations = mutableListOf<Rotation>()
    val revocations = mutableListOf<Revocation>()

    override fun findSessionByTokenHash(tokenHash: SecretHash): RefreshSession? {
        val session = found.getOrElse(lookups.size) { found.last() }
        lookups += tokenHash
        return session
    }

    override fun rotate(
        presentedHash: SecretHash,
        newHash: SecretHash,
        now: Instant,
    ): RefreshSession? {
        rotations += Rotation(presentedHash, newHash, now)
        return rotated
    }

    override fun revokeSession(
        sessionId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Boolean {
        revocations += Revocation(sessionId, reason, now)
        return revoked
    }

    override fun revokeAllSessions(
        principalId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Int = error("이 테스트에서는 쓰지 않음")

    override fun revokeAllSessionsExcept(
        principalId: UUID,
        keepSessionId: UUID,
        reason: RevokeReason,
        now: Instant,
    ): Int = error("이 테스트에서는 쓰지 않음")

    data class Rotation(
        val presentedHash: SecretHash,
        val newHash: SecretHash,
        val now: Instant,
    )

    data class Revocation(
        val sessionId: UUID,
        val reason: RevokeReason,
        val now: Instant,
    )
}
