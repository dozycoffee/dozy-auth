package com.dozycoffee.auth.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrincipalKeyTest {
    @Test
    fun `sub는 소문자 type과 id를 콜론으로 이음`() {
        assertEquals("employee:42", PrincipalKey(PrincipalType.EMPLOYEE, 42).sub)
    }

    @ParameterizedTest
    @EnumSource(PrincipalType::class)
    fun `sub를 만들고 다시 파싱하면 같은 key`(type: PrincipalType) {
        for (id in listOf(PrincipalKey.MIN_ID, 42L, PrincipalKey.MAX_ID)) {
            val key = PrincipalKey(type, id)
            assertEquals(key, PrincipalKey.fromSub(key.sub))
        }
    }

    @Test
    fun `MAX_ID는 JavaScript Number MAX_SAFE_INTEGER와 같음`() {
        assertEquals(9_007_199_254_740_991, PrincipalKey.MAX_ID)
    }

    @ParameterizedTest
    @ValueSource(longs = [PrincipalKey.MIN_ID, PrincipalKey.MAX_ID])
    fun `id 경계값 MIN_ID, MAX_ID는 허용`(id: Long) {
        assertEquals(id, PrincipalKey(PrincipalType.EMPLOYEE, id).id)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, PrincipalKey.MAX_ID + 1, Long.MAX_VALUE])
    fun `id가 MIN_ID 이상 MAX_ID 이하가 아니면 거부`(id: Long) {
        assertFailsWith<IllegalArgumentException> { PrincipalKey(PrincipalType.EMPLOYEE, id) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "employee:042",
            "employee:+42",
            "employee:-42",
            "employee:0",
            "EMPLOYEE:42",
            "Employee:42",
            "employee:",
            ":42",
            "employee",
            "employee:4:2",
            "employee: 42",
            " employee:42",
            "employee:42 ",
            "unknown:1",
            "employee:${PrincipalKey.MAX_ID + 1}",
            "employee:99999999999999999999",
            "",
        ],
    )
    fun `정규형이 아닌 sub는 거부`(sub: String) {
        assertNull(PrincipalKey.fromSubOrNull(sub))
        assertFailsWith<IllegalArgumentException> { PrincipalKey.fromSub(sub) }
    }
}
