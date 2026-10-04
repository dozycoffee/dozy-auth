package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.RoleDefinition
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateRoleUseCase
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CountRoleHoldersPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.UpdateRolePort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * role 정의의 이름·설명 수정 (api/admin.md role 수정, GOV-13). system role은 `FORBIDDEN`입니다.
 * 없는 role(`NOT_FOUND`)을 먼저 보고, 관리 등급은 토큰이 아니라 DB의 현재 role로 정합니다 (GOV-14).
 *
 * 보낸 값만 바꾸며, 실제로 바뀐 값이 없으면 저장하지도 기록하지도 않습니다. 감사 로그는 `ROLE_UPDATED`이고 `detail.fields`에
 * 바뀐 필드 이름만 남깁니다 (AUD-07).
 */
@Service
class UpdateRoleService(
    private val loadRole: LoadRolePort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val updateRole: UpdateRolePort,
    private val countRoleHolders: CountRoleHoldersPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : UpdateRoleUseCase {
    @Transactional
    override fun updateRole(command: UpdateRoleCommand): RoleDefinition {
        val role = loadRole.findRoleById(command.roleId) ?: throw RoleNotFoundException()
        val manager = Manager(command.manager.id, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.manager.id)))
        ManagementPolicy.checkCanModifyRoleDefinition(manager, role)

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
