package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.RequestOwnerTransferCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.RequestOwnerTransferUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferRequestMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.OwnerTransferPolicy
import com.dozycoffee.auth.server.domain.authorization.TargetStatus
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.OwnerTransferPayload
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * owner 양도 요청 (api/admin.md owner 양도 요청, GOV-09, VER-01 `OWNER_TRANSFER`).
 *
 * 1. 요청한 직원의 principal 행을 먼저 잠급니다(`FOR NO KEY UPDATE`). 양도 요청·취소·수락은 모두 owner의 행을 먼저 잠그므로, 진행 중인
 *    양도 확인(GOV-09 "전체에서 하나")과 발급이 동시 요청에서도 차례로 처리됩니다. 그다음 대상 행을 잠급니다 (잠그는 순서: owner → 대상)
 * 2. 검사 순서: 대상 없음(`NOT_FOUND`) → DB의 현재 role로 owner인지(`FORBIDDEN`, GOV-14) → 대상이 자기 자신이거나 `ACTIVE` 직원이 아님
 *    (`INVALID_STATE`) → 살아 있는 `OWNER_TRANSFER`가 있음(`INVALID_STATE`)
 * 3. 대상 principal에 `OWNER_TRANSFER`를 발급합니다. `payload.requestedBy`에 요청한 owner의 id를 남겨 수락할 때 다시 확인합니다.
 *    수락 메일은 대상의 지금 이메일로 커밋 후 보냅니다 (architecture.md §9.3)
 * 4. 감사 로그 `OWNER_TRANSFER_REQUESTED`: 행위자는 owner, 대상은 양도 대상 직원 (AUD-08)
 *
 * 관리자가 고른 계정에 보내는 것이므로 이메일 단위 요청 제한(api/conventions.md §8)은 적용하지 않습니다.
 */
@Service
class RequestOwnerTransferService(
    private val lockAccount: LockAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val loadEmployee: LoadEmployeePort,
    private val loadVerification: LoadVerificationPort,
    private val issueVerification: IssueVerificationPort,
    private val sendMail: SendMailPort,
    private val principals: PrincipalAdministration,
    private val clock: Clock,
) : RequestOwnerTransferUseCase {
    @Transactional
    override fun requestOwnerTransfer(command: RequestOwnerTransferCommand): Instant {
        // 1. owner → 대상 순서로 잠급니다. 잠금을 기다린 동안 시간이 지났을 수 있으므로 잠근 뒤 시각을 읽습니다
        lockAccount.lockAccountById(command.ownerId)
        val target = lockAccount.lockAccountById(command.targetId) ?: throw PrincipalNotFoundException()
        val now = clock.instant()

        // 2. GOV-14, GOV-09
        val requester = Manager(command.ownerId, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.ownerId)))
        OwnerTransferPolicy.checkCanRequest(
            requester = requester,
            targetId = target.id,
            targetType = target.type,
            targetStatus = TargetStatus.valueOf(target.status.name),
            transferInProgress = loadVerification.findLive(VerificationPurpose.OWNER_TRANSFER, now).isNotEmpty(),
        )

        // 3. VER-01, VER-08 대상의 지금 이메일로 발급하고 커밋 후 보냅니다
        val profile = checkNotNull(loadEmployee.findEmployeeById(target.id)) { "직원 profile이 없습니다" }.profile
        val issued =
            NewVerification.issue(
                principalId = target.id,
                purpose = VerificationPurpose.OWNER_TRANSFER,
                target = profile.email,
                now = now,
                payload = OwnerTransferPayload.of(command.ownerId),
            )
        val saved = issueVerification.issue(issued.verification)
        sendMail.send(OwnerTransferRequestMail(profile.email, profile.name, issued.token, saved.expiresAt))

        // 4. AUD-08
        principals.record(AuditAction.OWNER_TRANSFER_REQUESTED, command.ownerId, target.id, now, command.ip, command.userAgent)
        return saved.expiresAt
    }
}
