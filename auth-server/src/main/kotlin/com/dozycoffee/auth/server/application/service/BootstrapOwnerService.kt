package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerCommand
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerOutcome
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerResult
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.CreateEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadOwnerPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LockOwnerBootstrapPort
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.Employee
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.domain.authorization.SystemRoles
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * owner 부트스트랩 (GOV-11, ADR-0010). 직원 초대와 같은 초대(`EMPLOYEE_INVITATION`)를 만들고 같은 수락 API를 씁니다.
 *
 * - 맨 먼저 부트스트랩 잠금을 얻습니다. 동시에 기동한 다른 인스턴스는 이 트랜잭션이 끝날 때까지 기다렸다가 만들어진 owner를 봅니다.
 *   잠금 밖에서 owner가 생겼으면 owner 유일성 인덱스(GOV-10)가 막아 [com.dozycoffee.auth.server.domain.authorization.OwnerAlreadyAssignedException]
 *   으로 전체를 되돌립니다.
 * - owner가 없으면 직원(`PENDING`) 생성, `auth:owner` 부여, 초대 발급, 감사 기록을 한 트랜잭션에서 하고 메일은 커밋 후 보냅니다.
 *   감사 로그는 `EMPLOYEE_INVITED`, `ROLE_GRANTED`이며 시스템 작업이라 행위자가 없습니다.
 * - 설정 이메일을 이미 다른 직원이 쓰고 있으면 그 직원을 owner로 만들지 않습니다. 초대 수락 없이 기존 계정에 owner 권한이 생기기 때문입니다.
 * - owner가 `PENDING`이고 살아 있는 초대가 없으면 owner의 이메일로 다시 발급합니다. 설정 이메일이 달라도 owner를 바꾸지 않습니다.
 *   재발급은 관리자의 초대 재발송(api/admin.md)처럼 감사 로그를 남기지 않습니다.
 * - owner 즉시 알림(AUD-01)은 보내지 않습니다. 받을 owner가 초대받는 본인이고 아직 수락 전이기 때문입니다.
 */
@Service
class BootstrapOwnerService(
    private val lockOwnerBootstrap: LockOwnerBootstrapPort,
    private val loadOwner: LoadOwnerPort,
    private val loadRole: LoadRolePort,
    private val loadEmployee: LoadEmployeePort,
    private val createEmployee: CreateEmployeePort,
    private val grantRole: GrantRolePort,
    private val loadVerification: LoadVerificationPort,
    private val issueVerification: IssueVerificationPort,
    private val sendMail: SendMailPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : BootstrapOwnerUseCase {
    @Transactional
    override fun bootstrap(command: BootstrapOwnerCommand): BootstrapOwnerResult {
        lockOwnerBootstrap.lockOwnerBootstrap()
        // 잠금을 기다린 동안 시간이 지났을 수 있으므로 잠금을 얻은 뒤 읽습니다
        val now = clock.instant()
        val ownerId = loadOwner.findOwnerId()
        return if (ownerId == null) inviteOwner(command.ownerEmail, now) else checkOwner(ownerId, command.ownerEmail, now)
    }

    private fun inviteOwner(
        email: Email?,
        now: Instant,
    ): BootstrapOwnerResult {
        if (email == null) return BootstrapOwnerResult(BootstrapOwnerOutcome.OWNER_EMAIL_MISSING)
        if (loadEmployee.findEmployeeByEmail(email) != null) return BootstrapOwnerResult(BootstrapOwnerOutcome.OWNER_EMAIL_IN_USE)

        val ownerRole = checkNotNull(loadRole.findRoleByCode(SystemRoles.OWNER)) { "auth:owner role이 없습니다 (마이그레이션 seed)" }
        val employee = createEmployee.createEmployee(email, OWNER_NAME, phone = null, address = null, createdAt = now)
        val id = employee.account.id
        grantRole.grant(RoleGrant(id, ownerRole.id, grantedBy = null, grantedAt = now))
        sendInvitation(employee, now)

        recordAuditLog.record(systemEvent(now, AuditAction.EMPLOYEE_INVITED, id))
        // AUD-08 부여한 role은 detail.roles에 담습니다
        recordAuditLog.record(
            systemEvent(now, AuditAction.ROLE_GRANTED, id, detail = mapOf("roles" to listOf(SystemRoles.OWNER.value))),
        )
        return BootstrapOwnerResult(BootstrapOwnerOutcome.OWNER_INVITED)
    }

    private fun checkOwner(
        ownerId: UUID,
        email: Email?,
        now: Instant,
    ): BootstrapOwnerResult {
        val owner = checkNotNull(loadEmployee.findEmployeeById(ownerId)) { "owner가 직원 계정이 아닙니다" }
        val ignored = email != null && email.lookupKey != owner.profile.email.lookupKey
        val outcome =
            when {
                owner.account.status != AccountStatus.PENDING -> BootstrapOwnerOutcome.OWNER_ACTIVE
                loadVerification.findLive(ownerId, VerificationPurpose.EMPLOYEE_INVITATION, now) != null ->
                    BootstrapOwnerOutcome.INVITATION_LIVE
                else -> {
                    sendInvitation(owner, now)
                    BootstrapOwnerOutcome.INVITATION_REISSUED
                }
            }
        return BootstrapOwnerResult(outcome, configuredEmailIgnored = ignored)
    }

    /** VER-01, VER-03 초대를 발급하고(이전 초대는 무효화) 커밋 후 메일을 보냅니다. */
    private fun sendInvitation(
        employee: Employee,
        now: Instant,
    ) {
        val profile = employee.profile
        val issued = NewVerification.issue(employee.account.id, VerificationPurpose.EMPLOYEE_INVITATION, profile.email, now)
        val saved = issueVerification.issue(issued.verification)
        sendMail.send(EmployeeInvitationMail(profile.email, profile.name, issued.token, saved.expiresAt))
    }

    private fun systemEvent(
        now: Instant,
        action: AuditAction,
        target: UUID,
        detail: Map<String, Any?> = emptyMap(),
    ) = AuditEvent(occurredAt = now, action = action, actor = null, target = AuditTarget.principal(target), detail = detail)

    private companion object {
        /** 부트스트랩 owner의 이름. 설정에는 이메일만 두므로(ADR-0010) 수락한 뒤 본인 정보 수정으로 바꿉니다. */
        const val OWNER_NAME = "Owner"
    }
}
