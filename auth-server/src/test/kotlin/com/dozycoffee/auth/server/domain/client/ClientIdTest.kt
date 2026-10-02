package com.dozycoffee.auth.server.domain.client

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** system client의 `client_id` 형식. CLI-01, ACC-04. */
class ClientIdTest {
    @ParameterizedTest
    @ValueSource(strings = ["svc-store", "svc-catalog", "svc-wms2", "svc-store-sync"])
    fun `CLI-01 svc-서비스명 형식이면 client_id로 받음`(value: String) {
        assertEquals(value, ClientId(value).value)
        assertEquals(value, ClientId.parseOrNull(value)?.value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "svc-",
            "store",
            "svc_store",
            "svc-Store",
            "SVC-store",
            "svc-1store",
            "svc-store-",
            "svc--store",
            "svc-store sync",
            "svc-store%20",
            "deleted-0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
        ],
    )
    fun `CLI-01 형식이 아니면 거부`(value: String) {
        assertFailsWith<IllegalArgumentException> { ClientId(value) }
        assertNull(ClientId.parseOrNull(value))
    }

    @Test
    fun `CLI-01 컬럼 길이를 넘으면 거부`() {
        val atLimit = "svc-" + "a".repeat(ClientId.MAX_LENGTH - "svc-".length)

        assertEquals(atLimit, ClientId.parseOrNull(atLimit)?.value)
        assertNull(ClientId.parseOrNull(atLimit + "a"))
    }
}
