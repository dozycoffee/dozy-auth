package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.credential.PasswordHash
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 비밀번호 credential 조회 (docs/data-model.md §3.5). */
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        const val HASH = "\$argon2id\$v=19\$m=1024,t=1,p=1\$c2FsdA\$aGFzaA"
    }
}
