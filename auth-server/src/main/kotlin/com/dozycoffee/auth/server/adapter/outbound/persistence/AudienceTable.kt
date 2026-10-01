package com.dozycoffee.auth.server.adapter.outbound.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** `audience` 테이블 (docs/data-model.md §3.7). */
internal object AudienceTable : Table("audience") {
    val id = long("id").autoIncrement()
    val code = varchar("code", 30)
    val name = varchar("name", 100)
    val description = varchar("description", 500).nullable()
    val createdAt = timestamp("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}
