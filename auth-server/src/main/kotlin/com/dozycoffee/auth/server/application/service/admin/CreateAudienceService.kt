package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.admin.CreateAudienceCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.CreateAudienceUseCase
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CreateAudiencePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.Audience
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * audience 추가 (api/admin.md audience 추가, GOV-13). owner만 할 수 있습니다. 웹 계층이 토큰의 role로 먼저 검사하고,
 * 여기서 DB의 현재 role로 다시 검사합니다 (GOV-14). 토큰이 만료 전이어도 회수된 owner 권한으로는 추가하지 못합니다.
 *
 * 감사 로그는 `AUDIENCE_CREATED`이고 `detail.audience`에 code를 남깁니다. owner에게 즉시 알립니다 (AUD-01, [OwnerAlerts]).
 */
@Service
class CreateAudienceService(
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val createAudience: CreateAudiencePort,
    private val recordAuditLog: RecordAuditLogPort,
    private val ownerAlerts: OwnerAlerts,
    private val clock: Clock,
) : CreateAudienceUseCase {
    @Transactional
    override fun createAudience(command: CreateAudienceCommand): Audience {
        // DOM-03 형식은 웹 계층이 먼저 검증합니다
        require(RoleCode.isValidCode(command.code)) { "audience code 형식이 올바르지 않습니다" }
        val manager = Manager(command.manager.id, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.manager.id)))
        ManagementPolicy.checkCanCreateAudience(manager)
        val now = clock.instant()
        val audience = createAudience.createAudience(command.code, command.name, command.description, now)

        val event =
            AuditEvent(
                occurredAt = now,
                action = AuditAction.AUDIENCE_CREATED,
                actor = AuditActor(command.manager.id, command.manager.type),
                target = AuditTarget.audience(audience.id),
                detail = mapOf("audience" to audience.code),
                ip = command.ip,
                userAgent = command.userAgent,
            )
        recordAuditLog.record(event)
        ownerAlerts.notifyIfRequired(event)
        return audience
    }
}
