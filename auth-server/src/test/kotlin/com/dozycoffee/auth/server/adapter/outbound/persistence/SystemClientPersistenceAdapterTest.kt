package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.ClientIdDuplicatedException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** system client 조회·등록·secret 재발급·파기 (docs/data-model.md §3.4). */
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

    @Test
    fun `CLI-04 등록하면 system 타입 ACTIVE principal과 client를 함께 만들고 secret 발급 시각은 등록 시각`() {
        val hash = SecretHash.of("secret")

        val created = adapter.createSystemClient(ClientId("svc-store"), "Store", hash, CREATED_AT)

        assertEquals(SystemClient(created.principalId, "svc-store", hash, "Store", CREATED_AT, CREATED_AT), created)
        assertEquals(created, adapter.findByClientId(ClientId("svc-store")))
        val principal = PrincipalTable.selectAll().where { PrincipalTable.id eq created.principalId }.single()
        assertEquals("SYSTEM", principal[PrincipalTable.type])
        assertEquals("ACTIVE", principal[PrincipalTable.status])
    }

    @Test
    fun `같은 client_id로 등록하면 CLIENT_ID_DUPLICATED이고 principal을 남기지 않으며 트랜잭션은 계속 쓸 수 있음`() {
        adapter.createSystemClient(ClientId("svc-store"), "Store", SecretHash.of("secret"), CREATED_AT)
        val principals = PrincipalTable.selectAll().count()

        assertFailsWith<ClientIdDuplicatedException> {
            adapter.createSystemClient(ClientId("svc-store"), "Store 2", SecretHash.of("other"), CREATED_AT)
        }

        assertEquals(principals, PrincipalTable.selectAll().count())
        adapter.createSystemClient(ClientId("svc-store-sync"), "Store sync", SecretHash.of("other"), CREATED_AT)
    }

    @Test
    fun `CLI-03 재발급하면 secret 해시와 발급 시각을 바꿔 이전 해시로는 인증되지 않음`() {
        val created = adapter.createSystemClient(ClientId("svc-store"), "Store", SecretHash.of("secret"), CREATED_AT)
        val newHash = SecretHash.of("new-secret")

        assertTrue(adapter.rotateSecret(created.principalId, newHash, ROTATED_AT))

        val found = checkNotNull(adapter.findByClientId(ClientId("svc-store")))
        assertEquals(newHash, found.secretHash)
        assertEquals(ROTATED_AT, found.secretRotatedAt)
        assertEquals(CREATED_AT, found.createdAt)
        assertNull(SystemClient.authenticate(found, SecretHash.of("secret")))
    }

    @Test
    fun `system client가 아니면 재발급할 것이 없어 false`() {
        assertFalse(adapter.rotateSecret(insertPrincipal(), SecretHash.of("secret"), ROTATED_AT))
    }

    @Test
    fun `모든 system client를 최근 등록한 순서로 조회하고 비활성화한 client도 포함`() {
        val older = adapter.createSystemClient(ClientId("svc-store"), "Store", SecretHash.of("a"), CREATED_AT)
        val newer = adapter.createSystemClient(ClientId("svc-wms"), "WMS", SecretHash.of("b"), ROTATED_AT)
        adapter.scrubSystemClient(older.principalId)

        val ids = adapter.findAll().map { it.principalId }

        assertEquals(listOf(newer.principalId, older.principalId), ids.filter { it == newer.principalId || it == older.principalId })
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
