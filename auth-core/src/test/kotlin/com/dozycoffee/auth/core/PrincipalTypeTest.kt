package com.dozycoffee.auth.core

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrincipalTypeTest {
    @ParameterizedTest
    @EnumSource(PrincipalType::class)
    fun `claim 값은 소문자이고 다시 찾으면 같은 type`(type: PrincipalType) {
        assertEquals(type.name.lowercase(), type.claimValue)
        assertEquals(type, PrincipalType.fromClaimValue(type.claimValue))
    }

    @ParameterizedTest
    @EnumSource(PrincipalType::class)
    fun `DOM-01 type은 자기 realm에서 허용됨`(type: PrincipalType) {
        assertTrue(type.realm.allows(type))
    }

    @ParameterizedTest
    @ValueSource(strings = ["EMPLOYEE", "Employee", "admin", "", "employee "])
    fun `모르는 claim 값은 거부`(value: String) {
        assertNull(PrincipalType.fromClaimValueOrNull(value))
        assertFailsWith<IllegalArgumentException> { PrincipalType.fromClaimValue(value) }
    }
}
