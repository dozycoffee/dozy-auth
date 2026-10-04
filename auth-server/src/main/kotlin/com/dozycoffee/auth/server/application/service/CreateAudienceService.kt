package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.CreateAudienceCommand
import com.dozycoffee.auth.server.application.port.inbound.CreateAudienceUseCase
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CreateAudiencePort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.Audience
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * audience 추가 (api/admin.md audience 추가, GOV-13). owner만 호출할 수 있으며 이 검사는 웹 계층이 합니다 (GOV-14).
 *
 * 감사 로그는 `AUDIENCE_CREATED`이고 `detail.audience`에 code를 남깁니다.
 */
@Service
class CreateAudienceService(
    private val createAudience: CreateAudiencePort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : CreateAudienceUseCase {
    @Transactional
    override fun createAudience(command: CreateAudienceCommand): Audience {
        // DOM-03 형식은 웹 계층이 먼저 검증합니다
        require(RoleCode.isValidCode(command.code)) { "audience code 형식이 올바르지 않습니다" }
        val now = clock.instant()
        val audience = createAudience.createAudience(command.code, command.name, command.description, now)

        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.AUDIENCE_CREATED,
                actor = AuditActor(command.manager.id, command.manager.type),
                target = AuditTarget.audience(audience.id),
                detail = mapOf("audience" to audience.code),
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
        return audience
    }
}
