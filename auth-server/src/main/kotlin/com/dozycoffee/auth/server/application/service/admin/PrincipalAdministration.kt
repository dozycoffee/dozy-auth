package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.ManagedTarget
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.TargetStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * 계정 상태와 비밀번호 관리 API(api/admin.md §3)가 함께 쓰는 대상 조회, 권한 검사, 감사 기록. 대상은 직원, 파트너, system client 모두입니다.
 *
 * 관리 등급(GOV-01)은 토큰이 아니라 DB의 현재 role로 정합니다 (GOV-14). 웹 계층의 필요 role 검사는 토큰으로 먼저 합니다.
 */
@Component
class PrincipalAdministration(
    private val lockAccount: LockAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val recordAuditLog: RecordAuditLogPort,
) {
    /**
     * 대상 principal을 잠그고 [action]을 할 수 있는지 검사합니다 (GOV-15: `NOT_FOUND` → GOV-03 → GOV-02).
     * 같은 계정에 대한 관리 작업이 동시에 오면 차례로 처리합니다. 계정 상태(ACC-01)는 호출하는 쪽이 이 검사 뒤에 판단합니다.
     *
     * @throws PrincipalNotFoundException 없는 principal
     */
    fun findManageable(
        managerId: UUID,
        principalId: UUID,
        action: ManagementAction,
    ): Account {
        val account = lockAccount.lockAccountById(principalId) ?: throw PrincipalNotFoundException()
        val manager = Manager(managerId, AdminGrade.of(loadPrincipalRoles.findRoleCodes(managerId)))
        val grade = AdminGrade.of(loadPrincipalRoles.findRoleCodes(account.id))
        val target = ManagedTarget(account.id, account.type, grade, TargetStatus.valueOf(account.status.name))
        ManagementPolicy.checkCanManage(manager, target, action)
        return account
    }

    /** 관리자가 대상 계정에 한 작업의 감사 기록 한 건 (AUD-08). 남긴 기록을 돌려주므로 owner 알림([OwnerAlerts])에 넘길 수 있습니다. */
    fun record(
        action: AuditAction,
        managerId: UUID,
        principalId: UUID,
        occurredAt: Instant,
        ip: String?,
        userAgent: String?,
        detail: Map<String, Any?> = emptyMap(),
    ): AuditEvent {
        val event =
            AuditEvent(
                occurredAt = occurredAt,
                action = action,
                actor = EmployeeAdministration.actor(managerId),
                target = AuditTarget.principal(principalId),
                detail = detail,
                ip = ip,
                userAgent = userAgent,
            )
        recordAuditLog.record(event)
        return event
    }
}
