package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.admin.DefineRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.DefineRoleUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.RoleDefinition
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CreateRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadAudiencePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.AudienceNotFoundException
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 일반 role 등록 (api/admin.md role 등록, GOV-13). system role은 마이그레이션으로만 만듭니다.
 *
 * 없는 audience(`NOT_FOUND`)를 먼저 보고, 관리 등급은 토큰이 아니라 DB의 현재 role로 정합니다 (GOV-14).
 *
 * 감사 로그는 `ROLE_DEFINED`이고 `detail.role`에 `{audience}:{code}`를 남깁니다.
 */
@Service
class DefineRoleService(
    private val loadAudience: LoadAudiencePort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val createRole: CreateRolePort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : DefineRoleUseCase {
    @Transactional
    override fun defineRole(command: DefineRoleCommand): RoleDefinition {
        // DOM-03 형식은 웹 계층이 먼저 검증합니다
        require(RoleCode.isValidCode(command.code)) { "role code 형식이 올바르지 않습니다" }
        val now = clock.instant()
        val audience = loadAudience.findAudienceByCode(command.audienceCode) ?: throw AudienceNotFoundException()
        val manager = Manager(command.manager.id, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.manager.id)))
        ManagementPolicy.checkCanDefineRole(manager)
        val role = createRole.createRole(audience, command.code, command.name, command.description, command.manager.id, now)

        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.ROLE_DEFINED,
                actor = AuditActor(command.manager.id, command.manager.type),
                target = AuditTarget.role(role.id),
                detail = mapOf("role" to role.code.value),
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
        return RoleDefinition(role, grantedCount = 0)
    }
}
