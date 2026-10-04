package com.dozycoffee.auth.server.adapter.outbound.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `refresh_session` 테이블 (docs/data-model.md §3.10). `id`는 DB가 UUID로 만듭니다. */
internal object RefreshSessionTable : Table("refresh_session") {
    val id = javaUUID("id").databaseGenerated()
    val principalId = javaUUID("principal_id").references(PrincipalTable.id)
    val realm = varchar("realm", 20)
    val currentTokenHash = char("current_token_hash", 64)
    val previousTokenHash = char("previous_token_hash", 64).nullable()
    val rotatedAt = timestamp("rotated_at").nullable()
    val createdAt = timestamp("created_at").databaseGenerated()
    val lastUsedAt = timestamp("last_used_at")
    val expiresAt = timestamp("expires_at")
    val absoluteExpiresAt = timestamp("absolute_expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val revokeReason = varchar("revoke_reason", 30).nullable()
    val userAgent = varchar("user_agent", 255).nullable()
    val ip = inet("ip").nullable()

    override val primaryKey = PrimaryKey(id)
}
