package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.ResendInvitationCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.ResendInvitationUseCase
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 관리자의 초대 재발송 (api/admin.md 초대 재발송, VER-01 `EMPLOYEE_INVITATION`, VER-03).
 *
 * - 검사 순서는 GOV-15를 따릅니다: 없는 직원(`NOT_FOUND`) → GOV-02 → 계정 상태(`PENDING`이 아니면 `INVALID_STATE`).
 * - 새 초대를 발급하면 이전 초대는 같은 트랜잭션에서 무효화되고(VER-03), 메일은 커밋 후 보냅니다 (architecture.md §9.3).
 *   받는 주소는 지금 profile의 이메일입니다.
 * - 감사 로그는 남기지 않습니다 (AUD-08). 초대 자체는 `EMPLOYEE_INVITED`로 이미 남았고, 계정·권한이 바뀌지 않기 때문입니다.
 *   부트스트랩의 초대 재발급(GOV-11)도 같습니다.
 */
@Service
class ResendInvitationService(
    private val employees: EmployeeAdministration,
    private val issueVerification: IssueVerificationPort,
    private val sendMail: SendMailPort,
    private val clock: Clock,
) : ResendInvitationUseCase {
    @Transactional
    override fun resendInvitation(command: ResendInvitationCommand): Instant {
        val now = clock.instant()
        val target = employees.findManageable(command.managerId, command.principalId, ManagementAction.RESEND_INVITATION)
        val (account, profile) = target.record.employee
        account.ensureInvitationPending()

        val issued = NewVerification.issue(account.id, VerificationPurpose.EMPLOYEE_INVITATION, profile.email, now)
        val saved = issueVerification.issue(issued.verification)
        sendMail.send(EmployeeInvitationMail(profile.email, profile.name, issued.token, saved.expiresAt))
        return saved.expiresAt
    }
}
