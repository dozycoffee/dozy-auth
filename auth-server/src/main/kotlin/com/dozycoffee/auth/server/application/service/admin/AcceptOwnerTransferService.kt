package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.application.port.inbound.admin.AcceptOwnerTransferCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.AcceptOwnerTransferUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadOwnerPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.RevokeRolePort
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferCompletedMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.verification.ConsumeVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.ForbiddenException
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.domain.authorization.SystemRoles
import com.dozycoffee.auth.server.domain.session.RevokeReason
import com.dozycoffee.auth.server.domain.verification.OwnerTransferPayload
import com.dozycoffee.auth.server.domain.verification.Verification
import com.dozycoffee.auth.server.domain.verification.VerificationExpiredException
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * owner 양도 수락 (api/admin.md owner 양도 수락, GOV-09, GOV-10).
 *
 * 1. 토큰 확인 (VER-04). 없거나 살아 있지 않거나 `OWNER_TRANSFER`가 아니면 `VERIFICATION_EXPIRED`
 * 2. 로그인한 직원이 양도 대상이 아니면 `FORBIDDEN`. 링크만 가로챈 사람이 owner가 되는 것을 막습니다 (GOV-09). 토큰은 소비하지 않습니다
 * 3. 요청한 owner → 수락하는 직원 순서로 principal 행을 잠급니다. 양도 요청·취소와 같은 순서와 잠금이라
 *    ([RequestOwnerTransferService]) 취소와 동시에 오면 먼저 끝난 쪽만 성공합니다
 * 4. 토큰 소비. 살아 있을 때만 소비하는 원자적 변경이라 같은 토큰을 동시에 쓰면 하나만 성공하고, 그사이 취소됐으면 `VERIFICATION_EXPIRED`
 * 5. 다시 확인: 요청한 owner(`payload.requestedBy`)가 지금도 owner이고, 수락하는 직원이 `ACTIVE`인지. 아니면 `VERIFICATION_EXPIRED`로
 *    모두 롤백합니다 (요청 뒤 수동 복구(GOV-12)로 owner가 바뀌었거나, 대상이 정지됨)
 * 6. 한 트랜잭션에서 기존 owner의 `auth:owner` 회수, 대상에게 부여(GOV-10 부분 UNIQUE 인덱스가 마지막 방어), 기존 owner의 모든 세션 폐기
 *    (`OWNER_TRANSFERRED`), 감사 로그 `OWNER_TRANSFERRED`. 행위자는 수락한 새 owner, 대상은 이전 owner이며, 함께 폐기한 세션이 있으면
 *    `detail.revokedSessions`(개수)를 남깁니다 (AUD-08)
 * 7. 이전 owner에게 완료 메일을 커밋 후 보냅니다 (AUD-03). owner 즉시 알림(AUD-01)은 이미 owner가 된 새 owner가 받습니다 ([OwnerAlerts])
 *
 * 새 owner의 토큰에는 다음 토큰 갱신부터 `auth:owner`가 담깁니다. 이전 owner의 access token은 만료까지 남지만(SES-07), 관리 API는
 * DB의 현재 role로 등급을 정하므로(GOV-14) owner 작업은 거부됩니다.
 */
@Service
class AcceptOwnerTransferService(
    private val loadVerification: LoadVerificationPort,
    private val consumeVerification: ConsumeVerificationPort,
    private val lockAccount: LockAccountPort,
    private val loadOwner: LoadOwnerPort,
    private val loadRole: LoadRolePort,
    private val revokeRole: RevokeRolePort,
    private val grantRole: GrantRolePort,
    private val revokeSessions: RevokeSessionsPort,
    private val loadEmployee: LoadEmployeePort,
    private val sendMail: SendMailPort,
    private val principals: PrincipalAdministration,
    private val ownerAlerts: OwnerAlerts,
    private val clock: Clock,
) : AcceptOwnerTransferUseCase {
    @Transactional
    override fun acceptOwnerTransfer(command: AcceptOwnerTransferCommand) {
        // 1. VER-04
        val found = loadVerification.findByTokenHash(SecretHash.of(command.token))
        val transfer = Verification.requireUsable(found, VerificationPurpose.OWNER_TRANSFER, clock.instant())

        // 2. GOV-09 대상 본인만 수락합니다
        if (transfer.principalId != command.principalId) throw ForbiddenException("양도 대상만 수락할 수 있습니다.")

        // 3. 요청한 owner → 대상 순서로 잠급니다. 잠금을 기다린 동안 시간이 지났을 수 있으므로 잠근 뒤 시각을 읽습니다
        val previousOwnerId = OwnerTransferPayload.requestedBy(transfer.payload) ?: throw VerificationExpiredException()
        lockAccount.lockAccountById(previousOwnerId)
        val newOwner = lockAccount.lockAccountById(command.principalId)
        val now = clock.instant()

        // 4. VER-04 살아 있을 때만 소비합니다
        if (!consumeVerification.consume(transfer.id, now)) throw VerificationExpiredException()

        // 5. 요청 뒤 바뀐 것이 없는지 다시 확인합니다
        if (loadOwner.findOwnerId() != previousOwnerId) throw VerificationExpiredException()
        if (newOwner == null || newOwner.type != PrincipalType.EMPLOYEE || newOwner.status != AccountStatus.ACTIVE) {
            throw VerificationExpiredException()
        }

        // 6. GOV-09, GOV-10 회수 뒤 부여합니다. 순서를 바꾸면 owner 유일성 인덱스에 걸립니다
        val ownerRole = checkNotNull(loadRole.findRoleByCode(SystemRoles.OWNER)) { "auth:owner role이 없습니다 (마이그레이션 seed)" }
        check(revokeRole.revoke(previousOwnerId, ownerRole.id)) { "잠근 owner에게 auth:owner가 없습니다" }
        grantRole.grant(RoleGrant(newOwner.id, ownerRole.id, grantedBy = previousOwnerId, grantedAt = now))
        val revokedSessions = revokeSessions.revokeAllSessions(previousOwnerId, RevokeReason.OWNER_TRANSFERRED, now)

        val event =
            principals.record(
                action = AuditAction.OWNER_TRANSFERRED,
                managerId = newOwner.id,
                principalId = previousOwnerId,
                occurredAt = now,
                ip = command.ip,
                userAgent = command.userAgent,
                detail = if (revokedSessions > 0) mapOf(REVOKED_SESSIONS to revokedSessions) else emptyMap(),
            )
        // AUD-01 받는 사람은 지금의 owner, 곧 새 owner입니다
        ownerAlerts.notifyIfRequired(event)

        // 7. AUD-03
        val previousOwner = loadEmployee.findEmployeeById(previousOwnerId)
        val newOwnerProfile = checkNotNull(loadEmployee.findEmployeeById(newOwner.id)) { "직원 profile이 없습니다" }.profile
        if (previousOwner != null) {
            sendMail.send(OwnerTransferCompletedMail(previousOwner.profile.email, newOwnerProfile.name, now))
        }
    }

    private companion object {
        const val REVOKED_SESSIONS = "revokedSessions"
    }
}
