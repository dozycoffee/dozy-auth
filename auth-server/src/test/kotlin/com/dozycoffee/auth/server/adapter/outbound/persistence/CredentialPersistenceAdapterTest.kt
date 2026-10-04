package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PasswordCredentialTable
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.credential.PasswordHash
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 비밀번호 credential 조회·생성 (docs/data-model.md §3.5). */
@PersistenceAdapterTest
class CredentialPersistenceAdapterTest {
    private val adapter = CredentialPersistenceAdapter()
    private val accounts = AccountPersistenceAdapter()

    @Test
    fun `저장된 비밀번호 해시를 principal id로 조회함`() {
        val id = accounts.createEmployee(Email("kim@dozycoffee.com"), "김도윤", null, null, NOW).account.id
        PasswordCredentialTable.insert {
            it[principalId] = id
            it[passwordHash] = HASH
        }

        assertEquals(PasswordHash(HASH), adapter.findPasswordHash(id))
    }

    @Test
    fun `비밀번호가 없는 principal은 null`() {
        val id = accounts.createEmployee(Email("kim@dozycoffee.com"), "김도윤", null, null, NOW).account.id

        assertNull(adapter.findPasswordHash(id))
    }

    @Test
    fun `비밀번호를 만들면 해시와 넘긴 시각을 변경·생성 시각으로 저장함`() {
        val id = accounts.createEmployee(Email("kim@dozycoffee.com"), "김도윤", null, null, NOW).account.id
        val createdAt = Instant.parse("2026-09-26T01:02:03Z")

        adapter.createPasswordCredential(id, PasswordHash(HASH), createdAt)

        assertEquals(PasswordHash(HASH), adapter.findPasswordHash(id))
        val row = PasswordCredentialTable.selectAll().where { PasswordCredentialTable.principalId eq id }.single()
        assertEquals(createdAt, row[PasswordCredentialTable.changedAt])
        assertEquals(createdAt, row[PasswordCredentialTable.createdAt])
    }

    @Test
    fun `ACC-04 비밀번호를 지우고 없으면 false`() {
        val id = accounts.createEmployee(Email("kim@dozycoffee.com"), "김도윤", null, null, NOW).account.id
        adapter.createPasswordCredential(id, PasswordHash(HASH), NOW)

        assertTrue(adapter.deletePasswordCredential(id))
        assertNull(adapter.findPasswordHash(id))
        assertFalse(adapter.deletePasswordCredential(id))
    }

    @Test
    fun `PWD-06 비밀번호를 바꾸면 해시와 변경 시각만 바뀌고 생성 시각은 그대로`() {
        val id = accounts.createEmployee(Email("kim@dozycoffee.com"), "김도윤", null, null, NOW).account.id
        adapter.createPasswordCredential(id, PasswordHash(HASH), NOW)
        val changedAt = Instant.parse("2026-09-26T01:02:03Z")

        assertTrue(adapter.updatePasswordHash(id, PasswordHash(NEW_HASH), changedAt))

        assertEquals(PasswordHash(NEW_HASH), adapter.findPasswordHash(id))
        val row = PasswordCredentialTable.selectAll().where { PasswordCredentialTable.principalId eq id }.single()
        assertEquals(changedAt, row[PasswordCredentialTable.changedAt])
        assertEquals(NOW, row[PasswordCredentialTable.createdAt])
    }

    @Test
    fun `비밀번호가 없는 principal의 비밀번호 변경은 false`() {
        val id = accounts.createEmployee(Email("kim@dozycoffee.com"), "김도윤", null, null, NOW).account.id

        assertFalse(adapter.updatePasswordHash(id, PasswordHash(NEW_HASH), NOW))
        assertNull(adapter.findPasswordHash(id))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        const val HASH = "\$argon2id\$v=19\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA"
        const val NEW_HASH = "\$argon2id\$v=19\$m=1024,t=1,p=1\$bmV3c2FsdA\$bmV3aGFzaA"
    }
}
