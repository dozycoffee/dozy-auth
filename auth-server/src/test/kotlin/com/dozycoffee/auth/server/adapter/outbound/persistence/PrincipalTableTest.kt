package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 영속성 테스트 기반의 예시: 테이블 객체로 insert한 행을 select로 읽습니다. */
@PersistenceAdapterTest
class PrincipalTableTest {
    @Test
    fun `테스트 시작 시 Flyway 마이그레이션이 적용되어 있음`() {
        val versions = mutableListOf<String>()
        TransactionManager.current().exec("SELECT version FROM flyway_schema_history WHERE success") { rs ->
            while (rs.next()) versions += rs.getString(1)
        }

        assertEquals(listOf("1"), versions)
    }

    @Test
    fun `principal을 저장하면 DB가 만든 id와 기본값으로 다시 읽을 수 있음`() {
        val id =
            PrincipalTable
                .insertReturning(listOf(PrincipalTable.id)) {
                    it[type] = "EMPLOYEE"
                    it[status] = "ACTIVE"
                }.single()[PrincipalTable.id]

        val row = PrincipalTable.selectAll().where { PrincipalTable.id eq id }.single()
        assertEquals(7, id.version())
        assertEquals("EMPLOYEE", row[PrincipalTable.type])
        assertEquals("ACTIVE", row[PrincipalTable.status])
        assertEquals(0, row[PrincipalTable.failedLoginCount])
        assertNull(row[PrincipalTable.lockedUntil])
        assertNotNull(row[PrincipalTable.createdAt])
    }

    @Test
    fun `employee_profile은 principal을 참조해 저장하고 읽음`() {
        val principalId =
            PrincipalTable
                .insertReturning(listOf(PrincipalTable.id)) {
                    it[type] = "EMPLOYEE"
                    it[status] = "PENDING"
                }.single()[PrincipalTable.id]

        EmployeeProfileTable.insert {
            it[EmployeeProfileTable.principalId] = principalId
            it[email] = "Kim@Dozy.com"
            it[name] = "김"
        }

        val row = EmployeeProfileTable.selectAll().where { EmployeeProfileTable.principalId eq principalId }.single()
        assertEquals("Kim@Dozy.com", row[EmployeeProfileTable.email])
        assertNull(row[EmployeeProfileTable.phone])
    }
}
