package com.dozycoffee.auth.core

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticatedPrincipalTest {
    @Test
    fun `DOM-01 realm이 허용하는 type이면 생성`() {
        val principal =
            AuthenticatedPrincipal(
                key = PrincipalKey(PrincipalType.EMPLOYEE, UUID.randomUUID()),
                realm = Realm.INTERNAL,
                roles = setOf("inbound_manager"),
                sessionId = "8c1d4f5f-2b9c-4e8a-a4d1-c9d3f2b7a6e0",
            )

        assertEquals(Realm.INTERNAL, principal.realm)
    }

    @Test
    fun `DOM-01 realm이 허용하지 않는 type이면 거부`() {
        assertFailsWith<IllegalArgumentException> {
            AuthenticatedPrincipal(PrincipalKey(PrincipalType.PARTNER, UUID.randomUUID()), Realm.INTERNAL, emptySet(), null)
        }
        assertFailsWith<IllegalArgumentException> {
            AuthenticatedPrincipal(PrincipalKey(PrincipalType.EMPLOYEE, UUID.randomUUID()), Realm.PARTNER, emptySet(), null)
        }
    }

    @Test
    fun `DOM-03 roles에는 prefix를 뗀 code만 허용`() {
        assertFailsWith<IllegalArgumentException> {
            AuthenticatedPrincipal(
                PrincipalKey(PrincipalType.EMPLOYEE, UUID.randomUUID()),
                Realm.INTERNAL,
                setOf("wms:inbound_manager"),
                null,
            )
        }
    }
}
