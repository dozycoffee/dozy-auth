package com.dozycoffee.auth.server.adapter.outbound.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `employee_profile` 테이블 (docs/data-model.md §3.2). */
internal object EmployeeProfileTable : Table("employee_profile") {
    val principalId = javaUUID("principal_id").references(PrincipalTable.id)
    val email = varchar("email", 254)
    val name = varchar("name", 50)
    val phone = varchar("phone", 20).nullable()
    val address = varchar("address", 255).nullable()
    val createdAt = timestamp("created_at").databaseGenerated()
    val updatedAt = timestamp("updated_at").databaseGenerated()

    override val primaryKey = PrimaryKey(principalId)
}
