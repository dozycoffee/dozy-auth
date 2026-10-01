package com.dozycoffee.auth.server.adapter.outbound.persistence

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `principal_role` 테이블 (docs/data-model.md §3.9). */
internal object PrincipalRoleTable : Table("principal_role") {
    val principalId = javaUUID("principal_id").references(PrincipalTable.id)
    val roleId = long("role_id").references(RoleTable.id)
    val grantedBy = javaUUID("granted_by").references(PrincipalTable.id).nullable()
    val grantedAt = timestamp("granted_at").databaseGenerated()

    override val primaryKey = PrimaryKey(principalId, roleId)
}
