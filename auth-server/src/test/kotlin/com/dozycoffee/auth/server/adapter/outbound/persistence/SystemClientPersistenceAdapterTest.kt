package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.SystemClient
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** system client 조회 (docs/data-model.md §3.4). */
@PersistenceAdapterTest
class SystemClientPersistenceAdapterTest {
    private val adapter = SystemClientPersistenceAdapter()

    @Test
    fun `client_id로 system client를 조회함`() {
        val principalId = insertPrincipal()
        val hash = SecretHash.of("secret")
        insertClient(principalId, "svc-store", hash.hex)

        val found = adapter.findByClientId(ClientId("svc-store"))

        assertEquals(SystemClient(principalId, "svc-store", hash, "Store", ROTATED_AT, CREATED_AT), found)
    }

    @Test
    fun `ACC-04 secret 해시가 지워진 client는 해시 없이 조회함`() {
        val principalId = insertPrincipal()
        insertClient(principalId, "svc-store", null)

        assertNull(adapter.findByClientId(ClientId("svc-store"))?.secretHash)
    }

    @Test
    fun `없는 client_id면 null`() {
        assertNull(adapter.findByClientId(ClientId("svc-unknown")))
    }

    @Test
    fun `ACC-04 파기하면 client_id를 deleted-id로 바꾸고 secret 해시를 지워 원래 client_id로 찾을 수 없음`() {
        val principalId = insertPrincipal()
        insertClient(principalId, "svc-store", SecretHash.of("secret").hex)

        assertTrue(adapter.scrubSystemClient(principalId))

        assertNull(adapter.findByClientId(ClientId("svc-store")))
        val row = SystemClientTable.selectAll().where { SystemClientTable.principalId eq principalId }.single()
        assertEquals("deleted-$principalId", row[SystemClientTable.clientId])
        assertNull(row[SystemClientTable.clientSecretHash])
        // 같은 client_id로 다시 등록할 수 있음
        insertClient(insertPrincipal(), "svc-store", SecretHash.of("new-secret").hex)
    }

    @Test
    fun `system client가 아니면 파기할 것이 없어 false`() {
        assertFalse(adapter.scrubSystemClient(insertPrincipal()))
    }

    private fun insertPrincipal(): UUID =
        PrincipalTable
            .insertReturning(listOf(PrincipalTable.id)) {
                it[type] = "SYSTEM"
                it[status] = "ACTIVE"
            }.single()[PrincipalTable.id]

    private fun insertClient(
        principalId: UUID,
        clientId: String,
        secretHash: String?,
    ) {
        SystemClientTable.insert {
            it[SystemClientTable.principalId] = principalId
            it[SystemClientTable.clientId] = clientId
            it[clientSecretHash] = secretHash
            it[name] = "Store"
            it[secretRotatedAt] = ROTATED_AT
            it[createdAt] = CREATED_AT
        }
    }

    private companion object {
        val ROTATED_AT: Instant = Instant.parse("2026-09-25T01:00:00Z")
        val CREATED_AT: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
