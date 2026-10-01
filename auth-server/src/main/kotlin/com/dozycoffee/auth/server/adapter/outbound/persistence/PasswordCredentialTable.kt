package com.dozycoffee.auth.server.adapter.outbound.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `password_credential` 테이블 (docs/data-model.md §3.5). */
internal object PasswordCredentialTable : Table("password_credential") {
    val principalId = javaUUID("principal_id").references(PrincipalTable.id)
    val passwordHash = varchar("password_hash", 255)
    val changedAt = timestamp("changed_at").databaseGenerated()
    val createdAt = timestamp("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(principalId)
}
