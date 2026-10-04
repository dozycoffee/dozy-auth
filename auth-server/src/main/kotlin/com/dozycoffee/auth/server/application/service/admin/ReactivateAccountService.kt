package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.ReactivateAccountCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.ReactivateAccountUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.ChangeAccountStatusPort
import com.dozycoffee.auth.server.domain.account.InvalidAccountStateException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 정지 해제 (api/admin.md 정지 해제, ACC-01).
 *
 * - 검사 순서는 GOV-15를 따릅니다: 없는 principal(`NOT_FOUND`) → GOV-02 → 계정 상태(`SUSPENDED`가 아니면 `INVALID_STATE`).
 *   해제는 계정을 쓸 수 없게 만드는 작업이 아니므로 GOV-03 대상이 아닙니다.
 * - 정지할 때 폐기한 세션은 되살리지 않습니다. 해제된 계정은 다시 로그인합니다.
 * - 감사 로그: `ACCOUNT_REACTIVATED` (detail 없음)
 */
@Service
class ReactivateAccountService(
    private val principals: PrincipalAdministration,
    private val changeAccountStatus: ChangeAccountStatusPort,
    private val clock: Clock,
) : ReactivateAccountUseCase {
    @Transactional
    override fun reactivate(command: ReactivateAccountCommand) {
        val now = clock.instant()
        val account = principals.findManageable(command.managerId, command.principalId, ManagementAction.REACTIVATE)
        // ACC-01 SUSPENDED → ACTIVE
        val reactivated = account.reactivate()
        if (!changeAccountStatus.changeStatus(account.id, account.status, reactivated.status, now)) throw InvalidAccountStateException()

        principals.record(AuditAction.ACCOUNT_REACTIVATED, command.managerId, account.id, now, command.ip, command.userAgent)
    }
}
