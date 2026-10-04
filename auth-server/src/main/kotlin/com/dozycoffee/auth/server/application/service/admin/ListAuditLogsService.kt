package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.ListAuditLogsCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.ListAuditLogsUseCase
import com.dozycoffee.auth.server.application.port.outbound.audit.AuditLogQuery
import com.dozycoffee.auth.server.application.port.outbound.audit.LoadAuditLogsPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.domain.ForbiddenException
import com.dozycoffee.auth.server.domain.Page
import com.dozycoffee.auth.server.domain.audit.AuditLogEntry
import com.dozycoffee.auth.server.domain.audit.AuditQueryPeriod
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 감사 로그 조회 (api/admin.md 감사 로그 조회, AUD-04).
 *
 * owner인지는 토큰이 아니라 DB의 현재 role로 정합니다 (GOV-14). 토큰의 role은 만료까지 남아 있으므로(SES-07) 그사이 owner를
 * 양도한 사람이 감사 로그를 보지 못하게 하기 위해서입니다. 권한을 먼저 보고 기간을 검사합니다.
 */
@Service
class ListAuditLogsService(
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val loadAuditLogs: LoadAuditLogsPort,
    private val clock: Clock,
) : ListAuditLogsUseCase {
    @Transactional(readOnly = true)
    override fun listAuditLogs(command: ListAuditLogsCommand): Page<AuditLogEntry> {
        if (AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.managerId)) != AdminGrade.OWNER) throw ForbiddenException()
        val period = AuditQueryPeriod.resolve(command.from, command.to, clock.instant())
        return loadAuditLogs.findAuditLogs(
            AuditLogQuery(
                from = period.from,
                to = period.to,
                actorId = command.actorId,
                targetType = command.targetType,
                targetId = command.targetId,
                actions = command.actions,
                page = command.page,
            ),
        )
    }
}
