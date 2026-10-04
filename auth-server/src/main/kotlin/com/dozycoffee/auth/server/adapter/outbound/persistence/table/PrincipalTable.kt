package com.dozycoffee.auth.server.adapter.outbound.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `principal` 테이블 (docs/data-model.md §3.1). `id`는 DB가 UUIDv7으로 만듭니다. */
internal object PrincipalTable : Table("principal") {
    val id = javaUUID("id").databaseGenerated()
    val type = varchar("type", 20)
    val status = varchar("status", 20)
    val failedLoginCount = integer("failed_login_count").default(0)
    val lockedUntil = timestamp("locked_until").nullable()
    val createdAt = timestamp("created_at").databaseGenerated()
    val updatedAt = timestamp("updated_at").databaseGenerated()
    val deactivatedAt = timestamp("deactivated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
