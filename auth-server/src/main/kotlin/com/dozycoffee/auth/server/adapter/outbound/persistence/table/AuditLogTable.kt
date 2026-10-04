package com.dozycoffee.auth.server.adapter.outbound.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `audit_log` 테이블 (docs/data-model.md §3.11). FK가 없습니다 (AUD-06). */
internal object AuditLogTable : Table("audit_log") {
    val id = long("id").autoIncrement()
    val occurredAt = timestamp("occurred_at").databaseGenerated()
    val actorId = javaUUID("actor_id").nullable()
    val actorType = varchar("actor_type", 20).nullable()
    val action = varchar("action", 50)
    val targetType = varchar("target_type", 30).nullable()
    val targetId = varchar("target_id", 50).nullable()
    val detail = jsonbObject("detail").nullable()
    val ip = inet("ip").nullable()
    val userAgent = varchar("user_agent", 255).nullable()

    override val primaryKey = PrimaryKey(id)
}
