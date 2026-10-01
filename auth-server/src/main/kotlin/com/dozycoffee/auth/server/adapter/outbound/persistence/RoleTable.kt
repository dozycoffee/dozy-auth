package com.dozycoffee.auth.server.adapter.outbound.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `role` 테이블 (docs/data-model.md §3.8). */
internal object RoleTable : Table("role") {
    val id = long("id").autoIncrement()
    val audienceId = long("audience_id").references(AudienceTable.id)
    val code = varchar("code", 50)
    val name = varchar("name", 100)
    val description = varchar("description", 500).nullable()
    val isSystem = bool("is_system").default(false)
    val createdBy = javaUUID("created_by").references(PrincipalTable.id).nullable()
    val createdAt = timestamp("created_at").databaseGenerated()
    val updatedAt = timestamp("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}
