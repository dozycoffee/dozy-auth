package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.RoleDefinition
import com.dozycoffee.auth.server.application.port.inbound.UpdateRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.UpdateRoleUseCase
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CountRoleHoldersPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.UpdateRolePort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * role 정의의 이름·설명 수정 (api/admin.md role 수정, GOV-13). system role은 `FORBIDDEN`입니다.
 *
 * 보낸 값만 바꾸며, 실제로 바뀐 값이 없으면 저장하지도 기록하지도 않습니다. 감사 로그는 `ROLE_UPDATED`이고 `detail.fields`에
 * 바뀐 필드 이름만 남깁니다 (AUD-07).
 */
@Service
class UpdateRoleService(
    private val loadRole: LoadRolePort,
    private val updateRole: UpdateRolePort,
    private val countRoleHolders: CountRoleHoldersPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : UpdateRoleUseCase {
    @Transactional
    override fun updateRole(command: UpdateRoleCommand): RoleDefinition {
        val role = loadRole.findRoleById(command.roleId) ?: throw RoleNotFoundException()
        ManagementPolicy.checkCanModifyRoleDefinition(role)

        val name = command.name ?: role.name
        val description = if (command.description == null) role.description else command.description.ifEmpty { null }
        val changedFields =
            buildList {
                if (name != role.name) add("name")
                if (description != role.description) add("description")
            }
        if (changedFields.isEmpty()) return RoleDefinition(role, countRoleHolders.countHolders(role.id))

        val now = clock.instant()
        val updated = updateRole.updateDetails(role.id, name, description, now) ?: throw RoleNotFoundException()
        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.ROLE_UPDATED,
                actor = AuditActor(command.manager.id, command.manager.type),
                target = AuditTarget.role(role.id),
                detail = mapOf("role" to role.code.value, "fields" to changedFields),
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
        return RoleDefinition(updated, countRoleHolders.countHolders(role.id))
    }
}
