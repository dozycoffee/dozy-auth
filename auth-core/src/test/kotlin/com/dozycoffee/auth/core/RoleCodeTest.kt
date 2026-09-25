package com.dozycoffee.auth.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RoleCodeTest {
    @Test
    fun `DOM-03 audience와 code로 나눠 파싱`() {
        val role = RoleCode.parse("wms:inbound_manager")

        assertEquals("wms", role.audience)
        assertEquals("inbound_manager", role.code)
        assertEquals("wms:inbound_manager", role.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["auth:owner", "wms:stock_viewer2", "a:b", "catalog:menu_editor"])
    fun `DOM-03 형식에 맞는 role은 파싱 후 같은 값`(value: String) {
        assertEquals(value, RoleCode.parse(value).value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "wms",
            "wms:",
            ":inbound_manager",
            "wms:inbound:manager",
            "WMS:inbound_manager",
            "wms:Inbound_manager",
            "wms:1st_manager",
            "wms:_manager",
            "wms:inbound-manager",
            "wms: inbound_manager",
            "wms:inbound_manager ",
            "",
        ],
    )
    fun `DOM-03 형식에 맞지 않는 role은 거부`(value: String) {
        assertNull(RoleCode.parseOrNull(value))
        assertFailsWith<IllegalArgumentException> { RoleCode.parse(value) }
    }

    @Test
    fun `DOM-03 생성자도 형식을 검사`() {
        assertFailsWith<IllegalArgumentException> { RoleCode("WMS", "inbound_manager") }
        assertFailsWith<IllegalArgumentException> { RoleCode("wms", "inbound-manager") }
    }
}
