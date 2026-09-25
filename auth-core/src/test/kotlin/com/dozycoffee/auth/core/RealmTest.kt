package com.dozycoffee.auth.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RealmTest {
    @Test
    fun `DOM-01 realm별로 허용하는 principal type`() {
        val allowed =
            Realm.entries.associateWith { realm ->
                PrincipalType.entries.filter(realm::allows).toSet()
            }

        assertEquals(
            mapOf(
                Realm.INTERNAL to setOf(PrincipalType.EMPLOYEE, PrincipalType.SYSTEM),
                Realm.PARTNER to setOf(PrincipalType.PARTNER),
                Realm.CUSTOMER to setOf(PrincipalType.CUSTOMER),
            ),
            allowed,
        )
    }

    @ParameterizedTest
    @EnumSource(Realm::class)
    fun `pathValue로 찾으면 같은 realm`(realm: Realm) {
        assertEquals(realm, Realm.fromPathValue(realm.pathValue))
    }

    @ParameterizedTest
    @ValueSource(strings = ["INTERNAL", "Internal", "admin", "", " internal"])
    fun `모르는 pathValue는 거부`(value: String) {
        assertNull(Realm.fromPathValueOrNull(value))
        assertFailsWith<IllegalArgumentException> { Realm.fromPathValue(value) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://auth.dozycoffee.com", "https://auth.dozycoffee.com/"])
    fun `issuer는 base 주소 뒤에 realms 경로를 붙임`(base: String) {
        assertEquals("https://auth.dozycoffee.com/realms/internal", Realm.INTERNAL.issuer(base))
        assertEquals("https://auth.dozycoffee.com/realms/partner", Realm.PARTNER.issuer(base))
    }
}
