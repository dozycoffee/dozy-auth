package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.LogoutCommand
import com.dozycoffee.auth.server.application.port.inbound.LogoutUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.session.LoadRefreshSessionPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.session.RevokeReason
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 로그아웃 (api/auth.md 로그아웃, SES-06, SES-08).
 *
 * 쿠키의 토큰이 현재 토큰이든 직전 토큰이든 그 세션을 `LOGOUT`으로 폐기합니다. 로그아웃은 권한을 줄이기만 하므로 갱신처럼
 * 직전 토큰을 따로 판정하지 않습니다. 요청 경로와 다른 realm의 세션은 폐기하지 않습니다.
 *
 * 세션이 없거나 이미 만료·폐기됐으면 아무것도 바꾸지 않고 성공으로 끝납니다 (SES-08). `SESSION_REVOKED`는 이번 요청이
 * 세션을 폐기했을 때만 남깁니다 (AUD-08).
 */
@Service
class LogoutService(
    private val loadRefreshSession: LoadRefreshSessionPort,
    private val revokeSessions: RevokeSessionsPort,
    private val loadAccount: LoadAccountPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : LogoutUseCase {
    @Transactional
    override fun logout(command: LogoutCommand) {
        val now = clock.instant()
        val presented = command.refreshToken?.takeIf { it.isNotEmpty() }?.let(SecretHash::of) ?: return
        val session = loadRefreshSession.findSessionByTokenHash(presented)?.takeIf { it.realm == command.realm } ?: return

        // SES-06 살아 있는 세션만 폐기합니다. 이미 폐기·만료됐으면 false이고 그래도 성공입니다 (SES-08)
        if (!revokeSessions.revokeSession(session.id, RevokeReason.LOGOUT, now)) return

        val actor = loadAccount.findAccountById(session.principalId)?.let { AuditActor(it.id, it.type) }
        recordAuditLog.record(
            AuditEvent.sessionRevoked(
                occurredAt = now,
                principalId = session.principalId,
                actor = actor,
                realm = session.realm,
                sessionId = session.id,
                reason = RevokeReason.LOGOUT.name,
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
    }
}
