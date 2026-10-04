package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.application.port.outbound.audit.AuditLogPage
import com.dozycoffee.auth.server.application.port.outbound.audit.AuditLogQuery
import com.dozycoffee.auth.server.application.port.outbound.audit.LoadAuditLogsPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditLogEntry
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.audit.AuditTargetType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Component

/**
 * 감사 로그를 저장하고 조회합니다 (docs/data-model.md §3.11). FK가 없으므로 계정이 정리돼도 기록은 남습니다 (AUD-06).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 *
 * - `actor_type`, `action`, `target_type`은 enum 이름(대문자)으로 저장합니다.
 * - 빈 `detail`은 `NULL`로 저장하고, `NULL`은 빈 detail로 읽습니다.
 * - `user_agent`는 컬럼 길이를 넘으면 잘라서 저장합니다. 길이 초과로 INSERT가 실패하면 업무 트랜잭션까지 중단되기 때문입니다.
 */
@Component
class AuditPersistenceAdapter :
    RecordAuditLogPort,
    LoadAuditLogsPort {
    override fun record(event: AuditEvent) {
        AuditLogTable.insert {
            it[occurredAt] = event.occurredAt
            it[actorId] = event.actor?.id
            it[actorType] = event.actor?.type?.name
            it[action] = event.action.name
            it[targetType] = event.target?.type?.name
            it[targetId] = event.target?.id
            it[detail] = event.detail.ifEmpty { null }
            it[ip] = event.ip
            it[userAgent] = event.userAgent?.take(USER_AGENT_MAX_LENGTH)
        }
    }

    override fun findAuditLogs(query: AuditLogQuery): AuditLogPage {
        val condition = query.toCondition()
        val total = AuditLogTable.selectAll().where(condition).count()
        val entries =
            AuditLogTable
                .selectAll()
                .where(condition)
                .orderBy(AuditLogTable.occurredAt to SortOrder.DESC, AuditLogTable.id to SortOrder.DESC)
                .limit(query.size)
                .offset(query.page.toLong() * query.size)
                .map { it.toEntry() }
        return AuditLogPage(entries, total)
    }

    private fun AuditLogQuery.toCondition(): Op<Boolean> {
        var condition = (AuditLogTable.occurredAt greaterEq from) and (AuditLogTable.occurredAt less to)
        actorId?.let { condition = condition and (AuditLogTable.actorId eq it) }
        targetType?.let { condition = condition and (AuditLogTable.targetType eq it.name) }
        targetId?.let { condition = condition and (AuditLogTable.targetId eq it) }
        if (actions.isNotEmpty()) condition = condition and (AuditLogTable.action inList actions.map { it.name })
        return condition
    }

    private fun ResultRow.toEntry(): AuditLogEntry {
        val actorId = this[AuditLogTable.actorId]
        val actorType = this[AuditLogTable.actorType]
        val targetType = this[AuditLogTable.targetType]
        val targetId = this[AuditLogTable.targetId]
        return AuditLogEntry(
            id = this[AuditLogTable.id],
            event =
                AuditEvent(
                    occurredAt = this[AuditLogTable.occurredAt],
                    action = AuditAction.valueOf(this[AuditLogTable.action]),
                    actor = if (actorId != null && actorType != null) AuditActor(actorId, PrincipalType.valueOf(actorType)) else null,
                    target =
                        if (targetType != null && targetId != null) {
                            AuditTarget(AuditTargetType.valueOf(targetType), targetId)
                        } else {
                            null
                        },
                    detail = this[AuditLogTable.detail].orEmpty(),
                    ip = this[AuditLogTable.ip],
                    userAgent = this[AuditLogTable.userAgent],
                ),
        )
    }

    private companion object {
        /** `audit_log.user_agent` 길이 (varchar(255)). */
        const val USER_AGENT_MAX_LENGTH = 255
    }
}
