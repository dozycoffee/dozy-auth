package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.ManagedTarget
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.TargetStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * role 부여·회수(api/admin.md §4)가 함께 쓰는 대상 조회와 감사 기록.
 *
 * 관리 등급(GOV-01)은 토큰이 아니라 DB의 현재 role로 정합니다. 토큰의 role은 만료까지 남아 있으므로(SES-07) 그사이 해임된
 * admin이 role을 바꾸지 못하게 하기 위해서입니다. 웹 계층의 필요 role 검사(GOV-14)는 토큰으로 먼저 합니다.
 */
@Component
class RoleAssignment(
    private val lockAccount: LockAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val recordAuditLog: RecordAuditLogPort,
) {
    /**
     * 대상 principal을 잠그고 관리자와 대상의 등급을 구합니다. 같은 대상에 대한 부여·회수가 동시에 오면 차례로 처리합니다.
     *
     * @throws PrincipalNotFoundException 없는 principal (GOV-15: 다른 검사보다 먼저)
     */
    fun lockTarget(
        manager: PrincipalKey,
        principalId: UUID,
    ): Parties {
        val account = lockAccount.lockAccountById(principalId) ?: throw PrincipalNotFoundException()
        val roles = loadPrincipalRoles.findRoleCodes(account.id)
        return Parties(
            manager = Manager(manager.id, AdminGrade.of(loadPrincipalRoles.findRoleCodes(manager.id))),
            target = ManagedTarget(account.id, account.type, AdminGrade.of(roles), TargetStatus.valueOf(account.status.name)),
        )
    }

    /** AUD-08 `ROLE_GRANTED`·`ROLE_REVOKED` 한 건. [roles]는 이번에 실제로 부여·회수한 role입니다. */
    fun record(
        action: AuditAction,
        manager: PrincipalKey,
        principalId: UUID,
        roles: List<RoleCode>,
        occurredAt: Instant,
        ip: String?,
        userAgent: String?,
    ) {
        recordAuditLog.record(
            AuditEvent(
                occurredAt = occurredAt,
                action = action,
                actor = AuditActor(manager.id, manager.type),
                target = AuditTarget.principal(principalId),
                detail = mapOf("roles" to roles.map { it.value }),
                ip = ip,
                userAgent = userAgent,
            ),
        )
    }

    /** 관리 작업의 두 쪽. */
    data class Parties(
        val manager: Manager,
        val target: ManagedTarget,
    )
}
