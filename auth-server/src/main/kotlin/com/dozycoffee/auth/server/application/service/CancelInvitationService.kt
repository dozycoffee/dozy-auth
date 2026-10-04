package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.CancelInvitationCommand
import com.dozycoffee.auth.server.application.port.inbound.CancelInvitationUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.ChangeAccountStatusPort
import com.dozycoffee.auth.server.application.port.outbound.account.ScrubEmployeeProfilePort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.RevokeRolePort
import com.dozycoffee.auth.server.application.port.outbound.credential.DeletePasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.verification.InvalidateVerificationPort
import com.dozycoffee.auth.server.domain.account.InvalidAccountStateException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import com.dozycoffee.auth.server.domain.session.RevokeReason
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 초대 취소 (api/admin.md 초대 취소, ACC-06). `PENDING` 직원을 `DEACTIVATED`로 바꾸고 ACC-04를 한 트랜잭션에서 처리합니다.
 *
 * - 검사 순서는 GOV-15를 따릅니다: 없는 직원(`NOT_FOUND`) → GOV-03 → GOV-02 → 계정 상태(`PENDING`이 아니면 `INVALID_STATE`).
 * - ACC-04 처리: 상태 변경, 세션 폐기(`ACCOUNT_DEACTIVATED`), 비밀번호 삭제, role 회수, 살아 있는 verification 무효화, profile 파기.
 *   `PENDING` 직원은 로그인할 수 없고 비밀번호도 없으므로 세션 폐기와 비밀번호 삭제는 보통 아무것도 바꾸지 않습니다.
 *   `external_identity`는 고객 realm을 도입할 때 생기므로(data-model.md §3.12) 지금은 없습니다.
 * - 감사 로그는 `ACCOUNT_DEACTIVATED` 한 건이며 `detail.via = "invitation_cancelled"`입니다. 함께 폐기한 세션이 있으면
 *   `detail.revokedSessions`(개수)를 더합니다 (AUD-08). 회수한 role은 `ROLE_REVOKED`로 따로 남기지 않습니다.
 * - profile을 파기해 이메일이 비므로 같은 이메일로 다시 초대할 수 있습니다 (ACC-05).
 */
@Service
class CancelInvitationService(
    private val employees: EmployeeAdministration,
    private val changeAccountStatus: ChangeAccountStatusPort,
    private val revokeSessions: RevokeSessionsPort,
    private val deletePasswordCredential: DeletePasswordCredentialPort,
    private val revokeRole: RevokeRolePort,
    private val invalidateVerification: InvalidateVerificationPort,
    private val scrubEmployeeProfile: ScrubEmployeeProfilePort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : CancelInvitationUseCase {
    @Transactional
    override fun cancelInvitation(command: CancelInvitationCommand) {
        val now = clock.instant()
        val target = employees.findManageable(command.managerId, command.principalId, ManagementAction.CANCEL_INVITATION)
        val account = target.record.employee.account
        // ACC-06 PENDING → DEACTIVATED
        val deactivated = account.cancelInvitation(now)
        if (!changeAccountStatus.changeStatus(account.id, account.status, deactivated.status, now)) throw InvalidAccountStateException()

        // ACC-04
        val revokedSessions = revokeSessions.revokeAllSessions(account.id, RevokeReason.ACCOUNT_DEACTIVATED, now)
        deletePasswordCredential.deletePasswordCredential(account.id)
        revokeRole.revokeAll(account.id)
        invalidateVerification.invalidateAll(account.id, now)
        scrubEmployeeProfile.scrubEmployeeProfile(account.id, now)

        val detail =
            buildMap<String, Any?> {
                put("via", VIA_INVITATION_CANCELLED)
                if (revokedSessions > 0) put("revokedSessions", revokedSessions)
            }
        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.ACCOUNT_DEACTIVATED,
                actor = EmployeeAdministration.actor(command.managerId),
                target = AuditTarget.principal(account.id),
                detail = detail,
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
    }

    private companion object {
        /** api/admin.md 초대 취소의 `detail.via` 값. */
        const val VIA_INVITATION_CANCELLED = "invitation_cancelled"
    }
}
