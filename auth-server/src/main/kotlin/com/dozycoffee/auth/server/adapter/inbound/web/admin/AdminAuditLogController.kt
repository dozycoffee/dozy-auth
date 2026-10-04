package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.server.adapter.inbound.web.PageResponse
import com.dozycoffee.auth.server.application.port.inbound.admin.ListAuditLogsCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.ListAuditLogsUseCase
import com.dozycoffee.auth.server.domain.PageRequest
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditLogEntry
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.audit.AuditTargetType
import com.dozycoffee.auth.starter.CurrentPrincipal
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 감사 로그 조회 (api/admin.md §8, AUD-04).
 *
 * 토큰 검증과 직원 토큰 확인은 보안 설정의 관리 체인이 먼저 하고, 필요 role `auth:owner`(GOV-14)는 여기서 토큰으로 검사합니다.
 * owner인지는 UseCase가 DB의 현재 role로 다시 확인합니다.
 *
 * - `from`, `to`는 ISO 8601 시각입니다. 형식이 틀리면 `VALIDATION_FAILED`입니다.
 * - `action`은 쉼표로 나눈 action 이름이고, `targetType`과 함께 모르는 값이면 `VALIDATION_FAILED`입니다.
 * - 응답의 행위자와 대상은 id와 type만 담고, 이름·이메일 같은 개인정보는 담지 않습니다 (AUD-07). `userAgent`는 응답하지 않습니다.
 */
@RestController
@PreAuthorize(AdminRoleController.OWNER)
class AdminAuditLogController(
    private val listAuditLogs: ListAuditLogsUseCase,
) {
    @GetMapping(AUDIT_LOGS_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) actorId: UUID?,
        @RequestParam(required = false) targetType: AuditTargetType?,
        @RequestParam(required = false) @Size(max = AuditTarget.ID_MAX_LENGTH) targetId: String?,
        @RequestParam(name = "action", required = false) actions: List<AuditAction>?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_SIZE}") @Min(1) @Max(PageRequest.MAX_SIZE.toLong()) size: Int,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ): PageResponse<AuditLogResponse> {
        val command =
            ListAuditLogsCommand(
                managerId = principal.key.id,
                from = from,
                to = to,
                actorId = actorId,
                targetType = targetType,
                targetId = targetId?.trim()?.takeIf { it.isNotEmpty() },
                actions = actions.orEmpty().toSet(),
                page = PageRequest(page, size),
            )
        return PageResponse.of(listAuditLogs.listAuditLogs(command), AuditLogResponse::of)
    }

    companion object {
        const val AUDIT_LOGS_PATH = "/admin/audit-logs"
    }
}

/**
 * 감사 로그 한 건 (api/admin.md 감사 로그 조회). 행위자나 대상이 없으면 그 id와 type은 `null`이고, detail이 없으면 `null`입니다.
 *
 * @property actorType principal type의 claim 값 (예: `employee`)
 */
data class AuditLogResponse(
    val id: Long,
    val occurredAt: Instant,
    val actorId: UUID?,
    val actorType: String?,
    val action: String,
    val targetType: String?,
    val targetId: String?,
    val detail: Map<String, Any?>?,
    val ip: String?,
) {
    companion object {
        fun of(entry: AuditLogEntry): AuditLogResponse {
            val event = entry.event
            return AuditLogResponse(
                id = entry.id,
                occurredAt = event.occurredAt,
                actorId = event.actor?.id,
                actorType = event.actor?.type?.claimValue,
                action = event.action.name,
                targetType = event.target?.type?.name,
                targetId = event.target?.id,
                detail = event.detail.ifEmpty { null },
                ip = event.ip,
            )
        }
    }
}
