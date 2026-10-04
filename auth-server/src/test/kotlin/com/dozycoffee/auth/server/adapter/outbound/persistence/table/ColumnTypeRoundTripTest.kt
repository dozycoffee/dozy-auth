package com.dozycoffee.auth.server.adapter.outbound.persistence.table

import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** 커스텀 컬럼 타입(jsonb, inet)과 시각·고정 길이 문자열 컬럼이 값을 그대로 돌려주는지 확인합니다. */
@PersistenceAdapterTest
class ColumnTypeRoundTripTest {
    @Test
    fun `jsonb 컬럼은 중첩된 JSON 객체를 그대로 돌려줌`() {
        val detail = mapOf("roles" to listOf("wms:manager", "store:viewer"), "revokedSessions" to 2, "note" to null)

        AuditLogTable.insert {
            it[action] = "ROLE_GRANTED"
            it[AuditLogTable.detail] = detail
        }

        assertEquals(detail, AuditLogTable.selectAll().single()[AuditLogTable.detail])
    }

    @Test
    fun `inet 컬럼은 IPv4와 IPv6 주소를 문자열로 돌려줌`() {
        AuditLogTable.insert {
            it[action] = "LOGIN_SUCCEEDED"
            it[ip] = "203.0.113.7"
        }
        AuditLogTable.insert {
            it[action] = "LOGIN_FAILED"
            it[ip] = "2001:db8::1"
        }

        val ips = AuditLogTable.selectAll().map { it[AuditLogTable.ip] }
        assertEquals(setOf("203.0.113.7", "2001:db8::1"), ips.toSet())
    }

    @Test
    fun `timestamptz 컬럼은 Instant를 마이크로초 단위로 돌려줌`() {
        val lockedUntil = Instant.parse("2026-10-01T03:04:05.123456Z")

        PrincipalTable.insert {
            it[type] = "EMPLOYEE"
            it[status] = "SUSPENDED"
            it[PrincipalTable.lockedUntil] = lockedUntil
        }

        val stored = PrincipalTable.selectAll().where { PrincipalTable.lockedUntil eq lockedUntil }.single()
        assertEquals(lockedUntil, stored[PrincipalTable.lockedUntil])
    }

    @Test
    fun `char 컬럼은 해시 64자를 그대로 돌려줌`() {
        val hash = "a1".repeat(32)
        val principalId = insertPrincipal()

        VerificationTable.insert {
            it[VerificationTable.principalId] = principalId
            it[purpose] = "PASSWORD_RESET"
            it[method] = "EMAIL"
            it[target] = "a@b.c"
            it[tokenHash] = hash
            it[expiresAt] = Instant.parse("2026-10-02T00:00:00Z")
        }

        assertEquals(hash, VerificationTable.selectAll().single()[VerificationTable.tokenHash])
    }

    private fun insertPrincipal() =
        PrincipalTable
            .insertReturning(listOf(PrincipalTable.id)) {
                it[type] = "EMPLOYEE"
                it[status] = "ACTIVE"
            }.single()[PrincipalTable.id]
}
