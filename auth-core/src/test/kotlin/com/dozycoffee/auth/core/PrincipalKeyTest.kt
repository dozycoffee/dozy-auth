package com.dozycoffee.auth.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PrincipalKeyTest {
    private val v7Id = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")

    @Test
    fun `sub는 소문자 type과 소문자 UUID를 콜론으로 이음`() {
        assertEquals("employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", PrincipalKey(PrincipalType.EMPLOYEE, v7Id).sub)
    }

    @ParameterizedTest
    @EnumSource(PrincipalType::class)
    fun `sub를 만들고 다시 파싱하면 같은 key`(type: PrincipalType) {
        for (id in listOf(v7Id, UUID.randomUUID())) {
            val key = PrincipalKey(type, id)
            assertEquals(key, PrincipalKey.fromSub(key.sub))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f", // v7
            "3f2b9c1e-8a4d-4c1e-9d3f-2b7a6e0c1d4f", // v4
            "00000000-0000-0000-0000-000000000000",
        ],
    )
    fun `ADR-0028 버전과 관계없이 정규형 UUID면 id로 받음`(value: String) {
        assertEquals(UUID.fromString(value), PrincipalKey.parseId(value))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0199A3C4-7B2E-7C1A-9F3D-2B6E8A1C4D5F",
            "0199a3c47b2e7c1a9f3d2b6e8a1c4d5f",
            "1-1-1-1-1",
            "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5",
            "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f0",
            "{0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f}",
            "urn:uuid:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            " 0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            "0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5g",
            "42",
            "",
        ],
    )
    fun `ADR-0028 정규형 UUID가 아닌 id는 거부`(value: String) {
        assertNull(PrincipalKey.parseIdOrNull(value))
        assertFailsWith<IllegalArgumentException> { PrincipalKey.parseId(value) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "EMPLOYEE:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            "Employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            "employee:0199A3C4-7B2E-7C1A-9F3D-2B6E8A1C4D5F",
            "employee:0199a3c47b2e7c1a9f3d2b6e8a1c4d5f",
            "employee:42",
            "employee:",
            ":0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            "employee",
            "employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f:1",
            "employee: 0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            " employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            "employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f ",
            "unknown:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f",
            "",
        ],
    )
    fun `정규형이 아닌 sub는 거부`(sub: String) {
        assertNull(PrincipalKey.fromSubOrNull(sub))
        assertFailsWith<IllegalArgumentException> { PrincipalKey.fromSub(sub) }
    }
}
