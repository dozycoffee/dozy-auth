package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** 테이블 객체가 마이그레이션이 만든 스키마와 같은 테이블·컬럼을 정의하는지 확인합니다 (docs/data-model.md). */
@PersistenceAdapterTest
class TableDefinitionTest {
    private val tables: List<Table> =
        listOf(
            PrincipalTable,
            EmployeeProfileTable,
            SystemClientTable,
            PasswordCredentialTable,
            VerificationTable,
            AudienceTable,
            RoleTable,
            PrincipalRoleTable,
            RefreshSessionTable,
            AuditLogTable,
        )

    @Test
    fun `테이블 객체의 테이블과 컬럼 이름이 DB 스키마와 같음`() {
        val actual = mutableMapOf<String, MutableSet<String>>()
        TransactionManager.current().exec(
            "SELECT table_name, column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'",
        ) { rs ->
            while (rs.next()) actual.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
        }

        val defined = tables.associate { it.tableName to it.columns.map { column -> column.name }.toSet() }
        assertEquals(actual, defined)
    }
}
