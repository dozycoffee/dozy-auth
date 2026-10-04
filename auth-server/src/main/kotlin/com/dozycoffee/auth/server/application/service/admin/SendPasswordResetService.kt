package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.application.port.inbound.admin.SendPasswordResetCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.SendPasswordResetUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.mail.PasswordResetMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 비밀번호 재설정 메일 발송 (api/admin.md 비밀번호 재설정 메일 발송, PWD-05, VER-01 `PASSWORD_RESET`, VER-03).
 *
 * - 검사 순서는 GOV-15를 따릅니다: 없는 principal(`NOT_FOUND`) → GOV-02 → 계정 상태(`ACTIVE`가 아니거나 system client면 `INVALID_STATE`).
 * - 새 토큰을 발급하면 이전 재설정 토큰은 같은 트랜잭션에서 무효화되고(VER-03), 메일은 지금 profile의 이메일로 커밋 후 보냅니다
 *   (architecture.md §9.3). 비밀번호와 세션은 바꾸지 않습니다. 재설정은 메일 링크의 화면에서 본인이 합니다 (PWD-07).
 * - 관리자가 고른 계정에 보내는 것이므로 이메일 단위 요청 제한(api/conventions.md §8)은 적용하지 않습니다.
 * - 감사 로그: `PASSWORD_RESET_REQUESTED` (detail 없음)
 */
@Service
class SendPasswordResetService(
    private val principals: PrincipalAdministration,
    private val loadEmployee: LoadEmployeePort,
    private val issueVerification: IssueVerificationPort,
    private val sendMail: SendMailPort,
    private val clock: Clock,
) : SendPasswordResetUseCase {
    @Transactional
    override fun sendPasswordReset(command: SendPasswordResetCommand) {
        val now = clock.instant()
        val account = principals.findManageable(command.managerId, command.principalId, ManagementAction.SEND_PASSWORD_RESET)
        account.ensurePasswordResettable()
        val email = emailOf(account)

        val issued = NewVerification.issue(account.id, VerificationPurpose.PASSWORD_RESET, email, now)
        val saved = issueVerification.issue(issued.verification)
        sendMail.send(PasswordResetMail(email, account.type.realm, issued.token, saved.expiresAt))
        principals.record(AuditAction.PASSWORD_RESET_REQUESTED, command.managerId, account.id, now, command.ip, command.userAgent)
    }

    /** 메일을 받을 지금 profile의 이메일. 파트너 profile은 파트너 가입을 구현할 때 추가합니다. */
    private fun emailOf(account: Account): Email =
        when (account.type) {
            PrincipalType.EMPLOYEE -> checkNotNull(loadEmployee.findEmployeeById(account.id)) { "직원 profile이 없습니다" }.profile.email
            PrincipalType.PARTNER, PrincipalType.CUSTOMER, PrincipalType.SYSTEM -> error("아직 profile이 없는 계정 종류입니다: ${account.type}")
        }
}
