package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.DeleteRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.DeleteRoleUseCase
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CountRoleHoldersPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.DeleteRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.RevokeRolePort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.RoleInUseException
import com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * role 정의 삭제 (api/admin.md role 삭제, GOV-13).
 *
 * - 없는 role은 `NOT_FOUND`, system role은 `FORBIDDEN`, 부여된 principal이 있는데 `revokeAll`이 아니면 `ROLE_IN_USE`입니다.
 * - `revokeAll`이면 한 트랜잭션에서 모든 principal에게서 회수하고 삭제합니다. 회수할 principal이 owner·admin이어도 GOV-02를
 *   적용하지 않습니다. 계정 하나에 대한 변경이 아니라 role 정의를 없애는 작업이며, role 정의 관리는 admin의 권한이기 때문입니다 (GOV-13).
 * - 감사 로그: 회수한 principal마다 `ROLE_REVOKED`(`detail.roles`, `detail.via = "role_deleted"`), 마지막에 `ROLE_DELETED`
 *   (`detail.role`, `detail.revokedPrincipals` 개수). 세션은 폐기하지 않습니다 (SES-05).
 */
@Service
class DeleteRoleService(
    private val loadRole: LoadRolePort,
    private val countRoleHolders: CountRoleHoldersPort,
    private val revokeRole: RevokeRolePort,
    private val deleteRole: DeleteRolePort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : DeleteRoleUseCase {
    @Transactional
    override fun deleteRole(command: DeleteRoleCommand) {
        val role = loadRole.findRoleById(command.roleId) ?: throw RoleNotFoundException()
        ManagementPolicy.checkCanModifyRoleDefinition(role)
        if (!command.revokeAll) {
            val holders = countRoleHolders.countHolders(role.id)
            if (holders > 0) throw RoleInUseException(holders)
        }

        val now = clock.instant()
        val actor = AuditActor(command.manager.id, command.manager.type)
        val revoked = if (command.revokeAll) revokeRole.revokeFromAll(role.id) else emptyList()
        revoked.forEach { principalId ->
            recordAuditLog.record(
                AuditEvent(
                    occurredAt = now,
                    action = AuditAction.ROLE_REVOKED,
                    actor = actor,
                    target = AuditTarget.principal(principalId),
                    detail = mapOf("roles" to listOf(role.code.value), "via" to VIA_ROLE_DELETED),
                    ip = command.ip,
                    userAgent = command.userAgent,
                ),
            )
        }
        if (!deleteRole.deleteRole(role.id)) throw RoleNotFoundException()
        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.ROLE_DELETED,
                actor = actor,
                target = AuditTarget.role(role.id),
                detail = mapOf("role" to role.code.value, "revokedPrincipals" to revoked.size),
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
    }

    private companion object {
        /** 회수 기록이 role 삭제의 일괄 회수에서 나왔음을 나타내는 `detail.via` 값. */
        private const val VIA_ROLE_DELETED = "role_deleted"
    }
}
