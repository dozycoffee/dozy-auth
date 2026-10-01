package com.dozycoffee.auth.server.adapter.outbound.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `verification` 테이블 (docs/data-model.md §3.6). */
internal object VerificationTable : Table("verification") {
    val id = long("id").autoIncrement()
    val principalId = javaUUID("principal_id").references(PrincipalTable.id)
    val purpose = varchar("purpose", 30)
    val method = varchar("method", 10)
    val target = varchar("target", 254)
    val tokenHash = char("token_hash", 64)
    val payload = jsonbObject("payload").nullable()
    val attemptCount = integer("attempt_count").default(0)
    val maxAttempts = integer("max_attempts").nullable()
    val expiresAt = timestamp("expires_at")
    val consumedAt = timestamp("consumed_at").nullable()
    val invalidatedAt = timestamp("invalidated_at").nullable()
    val createdAt = timestamp("created_at").databaseGenerated()

    override val primaryKey = PrimaryKey(id)
}
