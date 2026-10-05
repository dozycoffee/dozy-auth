package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.server.application.port.inbound.auth.LoginResult
import com.dozycoffee.auth.server.application.port.inbound.auth.RefreshTokenCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.RefreshTokenUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.application.port.outbound.metrics.RecordMetricsPort
import com.dozycoffee.auth.server.application.port.outbound.metrics.TokenIssueKind
import com.dozycoffee.auth.server.application.port.outbound.session.LoadRefreshSessionPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.session.RotateRefreshSessionPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.session.RefreshDecision
import com.dozycoffee.auth.server.domain.session.RefreshSession
import com.dozycoffee.auth.server.domain.session.RevokeReason
import com.dozycoffee.auth.server.domain.session.SessionExpiredException
import com.dozycoffee.auth.server.domain.session.SessionRevokedException
import com.dozycoffee.auth.server.domain.session.TokenRotatedException
import com.dozycoffee.auth.server.domain.token.AccessTokenFactory
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 토큰 갱신 (api/auth.md 토큰 갱신, SES-03~SES-05).
 *
 * **판정 먼저, 그다음 교체** (data-model.md §3.10): 제시된 토큰의 해시로 세션을 찾아 `RefreshDecision.judge`로 판정하고(요청 경로의
 * realm 포함), `Rotate`일 때만 교체 쿼리를 실행합니다. 교체 쿼리는 현재 해시가 아직 그대로일 때만 바꾸므로(SES-04) 그 사이에 다른
 * 요청이 먼저 교체했으면 0행이 되고, 그때는 다시 찾아 한 번 더 판정합니다. 보통 직전 토큰이 되어 `TOKEN_ROTATED`입니다.
 *
 * 재사용 탐지(`SESSION_REVOKED`)는 에러 응답이어도 세션 폐기와 감사 기록이 남아야 하므로 `noRollbackFor`로 커밋합니다
 * (architecture.md §9.2). 그 예외는 폐기와 기록을 마친 뒤 마지막에 던지고, 그 경로에는 다른 변경이 없습니다.
 * 다른 에러(`TOKEN_ROTATED`, `SESSION_EXPIRED`)로 끝나는 경로는 아무것도 바꾸지 않습니다.
 *
 * 발급과 재사용 탐지는 지표로도 셉니다 (configuration.md §10).
 */
@Service
class RefreshTokenService(
    private val loadRefreshSession: LoadRefreshSessionPort,
    private val rotateRefreshSession: RotateRefreshSessionPort,
    private val revokeSessions: RevokeSessionsPort,
    private val loadAccount: LoadAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val signToken: SignTokenPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val recordMetrics: RecordMetricsPort,
    private val issuerBaseUri: IssuerBaseUri,
    private val clock: Clock,
) : RefreshTokenUseCase {
    @Transactional(noRollbackFor = [SessionRevokedException::class])
    override fun refresh(command: RefreshTokenCommand): LoginResult {
        val now = clock.instant()
        val presented = command.refreshToken?.takeIf { it.isNotEmpty() }?.let(SecretHash::of) ?: throw SessionExpiredException()
        val newToken = OpaqueSecret.generate()

        val (session, account) = rotate(command, presented, newToken.hash(), now)

        val claims =
            AccessTokenFactory.create(
                principal = PrincipalKey(account.type, account.id),
                realm = session.realm,
                // SES-05 role은 갱신할 때마다 다시 조회합니다
                roles = loadPrincipalRoles.findRoleCodes(account.id),
                sessionId = session.id.toString(),
                issuerBaseUri = issuerBaseUri,
                issuedAt = now,
                tokenId = UUID.randomUUID().toString(),
            )
        val accessToken = signToken.sign(claims)
        recordMetrics.tokenIssued(TokenIssueKind.REFRESH, session.realm)
        return LoginResult(
            accessToken = accessToken,
            expiresIn = AuthPolicy.ACCESS_TOKEN_TTL,
            refreshToken = newToken,
            refreshTokenMaxAge = session.remainingAbsoluteLifetime(now),
        )
    }

    /**
     * SES-03 판정하고 `Rotate`이면 교체합니다. 교체가 0행이면(다른 요청이 먼저 교체함) 다시 찾아 한 번 더 판정하며,
     * 그때도 `Rotate`이면 그 사이 상태를 알 수 없으므로 `SESSION_EXPIRED`로 끝냅니다.
     *
     * @return 교체한 뒤의 세션과 그 계정
     */
    private fun rotate(
        command: RefreshTokenCommand,
        presented: SecretHash,
        newHash: SecretHash,
        now: Instant,
    ): Pair<RefreshSession, Account> {
        val decision = judge(presented, command, now)
        val account = activeAccount(decision.session)
        rotateRefreshSession.rotate(presented, newHash, now)?.let { return it to account }

        // SES-04 다른 요청이 먼저 교체했거나 그 사이 폐기됨. 새로 찾아 다시 판정합니다
        judge(presented, command, now)
        throw SessionExpiredException()
    }

    /** SES-03 판정. `Rotate`가 아니면 그 결과에 맞는 예외로 끝냅니다. */
    private fun judge(
        presented: SecretHash,
        command: RefreshTokenCommand,
        now: Instant,
    ): RefreshDecision.Rotate =
        when (val decision = RefreshDecision.judge(loadRefreshSession.findSessionByTokenHash(presented), presented, command.realm, now)) {
            is RefreshDecision.Rotate -> decision
            RefreshDecision.TokenRotated -> throw TokenRotatedException()
            is RefreshDecision.ReuseDetected -> revokeForReuse(decision.session, command, now)
            RefreshDecision.Expired -> throw SessionExpiredException()
        }

    /**
     * SES-03 재사용 탐지: 세션을 `REUSE_DETECTED`로 폐기하고 `SESSION_REVOKED`를 남긴 뒤 `SESSION_REVOKED`로 응답합니다 (AUD-08).
     * 같은 토큰으로 동시에 들어온 다른 요청이 먼저 폐기했으면(0행) 이 요청은 폐기한 것이 아니므로 기록하지 않고 `SESSION_EXPIRED`입니다.
     */
    private fun revokeForReuse(
        session: RefreshSession,
        command: RefreshTokenCommand,
        now: Instant,
    ): Nothing {
        if (!revokeSessions.revokeSession(session.id, RevokeReason.REUSE_DETECTED, now)) throw SessionExpiredException()
        recordAuditLog.record(
            AuditEvent.sessionRevoked(
                occurredAt = now,
                principalId = session.principalId,
                actor = null,
                realm = session.realm,
                sessionId = session.id,
                reason = RevokeReason.REUSE_DETECTED.name,
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
        recordMetrics.refreshReuseDetected(session.realm)
        throw SessionRevokedException()
    }

    /**
     * SES-05 계정 상태를 다시 조회해 `ACTIVE`가 아니면 `SESSION_EXPIRED`입니다. 상태가 바뀔 때 세션은 이미 폐기되므로 방어용이며
     * 세션을 따로 폐기하지 않습니다. 교체 전에 확인하므로 거부할 때 세션은 바뀌지 않습니다.
     */
    private fun activeAccount(session: RefreshSession): Account =
        loadAccount.findAccountById(session.principalId)?.takeIf { it.status == AccountStatus.ACTIVE } ?: throw SessionExpiredException()
}
