package com.dozycoffee.auth.server.adapter.outbound.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `system_client` 테이블 (docs/data-model.md §3.4). */
internal object SystemClientTable : Table("system_client") {
    val principalId = javaUUID("principal_id").references(PrincipalTable.id)
    val clientId = varchar("client_id", 100)
    val clientSecretHash = char("client_secret_hash", 64).nullable()
    val name = varchar("name", 100)
    val secretRotatedAt = timestamp("secret_rotated_at")
    val createdAt = timestamp("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(principalId)
}
