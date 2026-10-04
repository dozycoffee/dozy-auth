package com.dozycoffee.auth.server.domain.client

import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.support.TokenFixtures.NOW
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** system client의 secret 인증. CLI-02, CLI-03, ACC-04, SEC-05. */
class SystemClientTest {
    @Test
    fun `CLI-02 저장된 해시와 제시한 secret의 해시가 같으면 인증`() {
        val client = client(secretHash = SecretHash.of(SECRET))

        assertSame(client, SystemClient.authenticate(client, SecretHash.of(SECRET)))
    }

    @Test
    fun `CLI-03 다른 secret이면 인증하지 않음`() {
        val client = client(secretHash = SecretHash.of(SECRET))

        assertNull(SystemClient.authenticate(client, SecretHash.of("previous-secret")))
    }

    @Test
    fun `ACC-04 secret 해시가 지워진 client는 어떤 secret으로도 인증하지 않음`() {
        val client = client(clientId = "deleted-${SYSTEM.id}", secretHash = null)

        assertNull(SystemClient.authenticate(client, SecretHash.of(SECRET)))
        assertNull(SystemClient.authenticate(client, SecretHash("0".repeat(64))))
    }

    @Test
    fun `client가 없으면 인증하지 않음`() {
        assertNull(SystemClient.authenticate(null, SecretHash.of(SECRET)))
        assertNull(SystemClient.authenticate(null, SecretHash("0".repeat(64))))
    }

    @Test
    fun `ACC-04 비활성화한 client의 client_id는 deleted-principal id`() {
        assertEquals("deleted-${SYSTEM.id}", SystemClient.deactivatedClientId(SYSTEM.id))
    }

    private fun client(
        clientId: String = "svc-store",
        secretHash: SecretHash?,
    ) = SystemClient(SYSTEM.id, clientId, secretHash, "Store", NOW, NOW)

    private companion object {
        const val SECRET = "dGVzdC1zZWNyZXQtZm9yLXN5c3RlbS1jbGllbnQtdGVzdA"
    }
}
