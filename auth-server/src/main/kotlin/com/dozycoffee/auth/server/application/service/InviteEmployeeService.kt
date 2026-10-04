package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.InviteEmployeeCommand
import com.dozycoffee.auth.server.application.port.inbound.InviteEmployeeUseCase
import com.dozycoffee.auth.server.application.port.inbound.InvitedEmployee
import com.dozycoffee.auth.server.application.port.outbound.account.CreateEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LockRolePort
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 관리자의 직원 초대 (api/admin.md 직원 초대, VER-01 `EMPLOYEE_INVITATION`, GOV-05~08, GOV-15, AUD-08).
 *
 * - 검사 순서: 없는 role(`NOT_FOUND`) → 관리 등급·GOV-05(`FORBIDDEN`) → 이메일 중복(`DUPLICATE_EMAIL`). 권한 검사를 모두
 *   통과한 뒤에 계정을 만듭니다. 관리 등급은 DB의 현재 role로 정합니다 (GOV-14).
 * - 직원(`PENDING`) 생성, role 부여, 초대 발급, 감사 기록을 한 트랜잭션에서 하므로 하나라도 실패하면 아무것도 남지 않고(GOV-08),
 *   메일은 커밋 후 보냅니다 (architecture.md §9.3). role 정의를 부여용으로 잠가 동시에 삭제된 role은 `NOT_FOUND`가 됩니다 (LockRolePort).
 * - 감사 로그는 `EMPLOYEE_INVITED`와, role을 지정했으면 `ROLE_GRANTED`(`detail.roles`)입니다 (AUD-08). owner 부트스트랩(GOV-11)과 같습니다.
 *   owner 알림(AUD-01)은 알림 기능 전까지 감사 기록만 남깁니다.
 */
@Service
class InviteEmployeeService(
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val lockRole: LockRolePort,
    private val createEmployee: CreateEmployeePort,
    private val grantRole: GrantRolePort,
    private val issueVerification: IssueVerificationPort,
    private val sendMail: SendMailPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : InviteEmployeeUseCase {
    @Transactional
    override fun inviteEmployee(command: InviteEmployeeCommand): InvitedEmployee {
        val roles = if (command.roles.isEmpty()) emptyList() else lockRole.lockRolesForGrant(command.roles)
        if (roles.size != command.roles.size) throw RoleNotFoundException()
        val manager = Manager(command.managerId, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.managerId)))
        ManagementPolicy.checkCanInvite(manager, command.roles)

        // role 잠금을 기다린 동안 시간이 지났을 수 있으므로 잠금을 얻은 뒤 읽습니다
        val now = clock.instant()
        val employee = createEmployee.createEmployee(command.email, command.name, command.phone, command.address, now)
        val (account, profile) = employee
        roles.forEach { grantRole.grant(RoleGrant(account.id, it.id, manager.id, now)) }

        val issued = NewVerification.issue(account.id, VerificationPurpose.EMPLOYEE_INVITATION, profile.email, now)
        val saved = issueVerification.issue(issued.verification)
        sendMail.send(EmployeeInvitationMail(profile.email, profile.name, issued.token, saved.expiresAt))

        record(command, AuditAction.EMPLOYEE_INVITED, account.id, now)
        if (roles.isNotEmpty()) {
            val granted = roles.map { it.code.value }.sorted()
            record(command, AuditAction.ROLE_GRANTED, account.id, now, mapOf("roles" to granted))
        }
        return InvitedEmployee(account.id, account.status, saved.expiresAt)
    }

    private fun record(
        command: InviteEmployeeCommand,
        action: AuditAction,
        principalId: UUID,
        now: Instant,
        detail: Map<String, Any?> = emptyMap(),
    ) {
        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = action,
                actor = EmployeeAdministration.actor(command.managerId),
                target = AuditTarget.principal(principalId),
                detail = detail,
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
    }
}
