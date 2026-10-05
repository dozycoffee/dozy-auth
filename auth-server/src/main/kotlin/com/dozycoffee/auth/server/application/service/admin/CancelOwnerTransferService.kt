package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.CancelOwnerTransferCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.CancelOwnerTransferUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.verification.InvalidateVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.OwnerTransferNotFoundException
import com.dozycoffee.auth.server.domain.authorization.OwnerTransferPolicy
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * owner 양도 취소 (api/admin.md owner 양도 취소, GOV-09).
 *
 * - 요청한 직원의 principal 행을 먼저 잠급니다. 양도 요청·수락과 같은 잠금이라([RequestOwnerTransferService]) 수락과 동시에 오면 차례로
 *   처리되고, 먼저 끝난 쪽만 성공합니다 (취소가 먼저면 수락은 `VERIFICATION_EXPIRED`, 수락이 먼저면 취소는 `NOT_FOUND`).
 * - 검사 순서: DB의 현재 role로 owner인지(`FORBIDDEN`, GOV-14) → 진행 중인(살아 있는) 양도가 있는지(`NOT_FOUND`).
 *   만료된 양도는 진행 중이 아니므로 `NOT_FOUND`입니다.
 * - 양도를 무효화하면 발송된 수락 링크는 `VERIFICATION_EXPIRED`가 됩니다 (VER-04).
 * - 감사 로그 `OWNER_TRANSFER_CANCELLED`: 행위자는 owner, 대상은 양도 대상 직원 (AUD-08). owner 본인에게 즉시 알림 (AUD-01, [OwnerAlerts])
 */
@Service
class CancelOwnerTransferService(
    private val lockAccount: LockAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val loadVerification: LoadVerificationPort,
    private val invalidateVerification: InvalidateVerificationPort,
    private val principals: PrincipalAdministration,
    private val ownerAlerts: OwnerAlerts,
    private val clock: Clock,
) : CancelOwnerTransferUseCase {
    @Transactional
    override fun cancelOwnerTransfer(command: CancelOwnerTransferCommand) {
        lockAccount.lockAccountById(command.ownerId)
        val now = clock.instant()

        OwnerTransferPolicy.checkCanCancel(Manager(command.ownerId, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.ownerId))))
        val live = loadVerification.findLive(VerificationPurpose.OWNER_TRANSFER, now)
        if (live.isEmpty()) throw OwnerTransferNotFoundException()

        // GOV-09 진행 중인 양도는 하나이지만, 있으면 모두 무효화합니다
        val cancelled =
            live.filter { invalidateVerification.invalidate(it.id, now) }.map { transfer ->
                principals.record(
                    AuditAction.OWNER_TRANSFER_CANCELLED,
                    command.ownerId,
                    transfer.principalId,
                    now,
                    command.ip,
                    command.userAgent,
                )
            }
        cancelled.firstOrNull()?.let { ownerAlerts.notifyIfRequired(it, cancelled.drop(1)) }
    }
}
