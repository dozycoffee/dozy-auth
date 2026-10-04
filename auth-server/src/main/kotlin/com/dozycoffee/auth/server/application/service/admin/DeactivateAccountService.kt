package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.DeactivateAccountCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.DeactivateAccountUseCase
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 계정 비활성화 (api/admin.md 계정 비활성화, ACC-01, ACC-04, CLI-04).
 *
 * - 검사 순서는 GOV-15를 따릅니다: 없는 principal(`NOT_FOUND`) → GOV-03 → GOV-02 → 계정 상태(이미 `DEACTIVATED`면 `INVALID_STATE`).
 * - `PENDING` 계정도 비활성화합니다. 직원의 초대 취소([CancelInvitationService])와 같은 ACC-04 처리([AccountDeactivation])를 씁니다.
 * - system client는 `client_id`를 `deleted-{id}`로 바꾸고 secret 해시를 지워 폐기합니다. 이후 토큰 발급은 `invalid_client`입니다.
 * - 감사 로그는 `ACCOUNT_DEACTIVATED` 한 건이며 `detail.reason`(요청의 사유)을 남기고, 폐기한 세션이 있으면
 *   `detail.revokedSessions`(개수)를 더합니다 (AUD-08). 회수한 role은 `ROLE_REVOKED`로 따로 남기지 않습니다.
 */
@Service
class DeactivateAccountService(
    private val principals: PrincipalAdministration,
    private val deactivation: AccountDeactivation,
    private val clock: Clock,
) : DeactivateAccountUseCase {
    @Transactional
    override fun deactivate(command: DeactivateAccountCommand) {
        val now = clock.instant()
        val account = principals.findManageable(command.managerId, command.principalId, ManagementAction.DEACTIVATE)
        val revokedSessions = deactivation.deactivate(account, now)

        val detail =
            buildMap<String, Any?> {
                put("reason", command.reason)
                if (revokedSessions > 0) put("revokedSessions", revokedSessions)
            }
        principals.record(AuditAction.ACCOUNT_DEACTIVATED, command.managerId, account.id, now, command.ip, command.userAgent, detail)
    }
}
