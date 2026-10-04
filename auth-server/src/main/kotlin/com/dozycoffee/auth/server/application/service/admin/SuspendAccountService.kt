package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.SuspendAccountCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.SuspendAccountUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.ChangeAccountStatusPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.domain.account.InvalidAccountStateException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import com.dozycoffee.auth.server.domain.session.RevokeReason
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 계정 정지 (api/admin.md 계정 정지, ACC-01, ACC-03).
 *
 * - 검사 순서는 GOV-15를 따릅니다: 없는 principal(`NOT_FOUND`) → GOV-03 → GOV-02 → 계정 상태(`ACTIVE`가 아니면 `INVALID_STATE`).
 * - 상태 변경과 세션 폐기(`ACCOUNT_SUSPENDED`)를 한 트랜잭션에서 합니다. 이미 발급된 access token은 만료까지 유효합니다 (SES-07).
 * - 감사 로그는 `ACCOUNT_SUSPENDED` 한 건이며 `detail.reason`(요청의 사유)을 남기고, 폐기한 세션이 있으면
 *   `detail.revokedSessions`(개수)를 더합니다 (AUD-08).
 */
@Service
class SuspendAccountService(
    private val principals: PrincipalAdministration,
    private val changeAccountStatus: ChangeAccountStatusPort,
    private val revokeSessions: RevokeSessionsPort,
    private val clock: Clock,
) : SuspendAccountUseCase {
    @Transactional
    override fun suspend(command: SuspendAccountCommand) {
        val now = clock.instant()
        val account = principals.findManageable(command.managerId, command.principalId, ManagementAction.SUSPEND)
        // ACC-01 ACTIVE → SUSPENDED
        val suspended = account.suspend()
        if (!changeAccountStatus.changeStatus(account.id, account.status, suspended.status, now)) throw InvalidAccountStateException()
        // ACC-03
        val revokedSessions = revokeSessions.revokeAllSessions(account.id, RevokeReason.ACCOUNT_SUSPENDED, now)

        val detail =
            buildMap<String, Any?> {
                put("reason", command.reason)
                if (revokedSessions > 0) put("revokedSessions", revokedSessions)
            }
        principals.record(AuditAction.ACCOUNT_SUSPENDED, command.managerId, account.id, now, command.ip, command.userAgent, detail)
    }
}
