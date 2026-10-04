package com.dozycoffee.auth.server.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 목록 페이지 (api/conventions.md §3). 기본값과 상한은 명세의 값입니다. */
class PagingTest {
    @Test
    fun `페이지는 0번부터 20개씩이 기본이고 크기는 100까지`() {
        assertEquals(PageRequest(0, 20), PageRequest())
        PageRequest(0, 100)
        assertFailsWith<IllegalArgumentException> { PageRequest(0, 101) }
        assertFailsWith<IllegalArgumentException> { PageRequest(0, 0) }
        assertFailsWith<IllegalArgumentException> { PageRequest(-1, 20) }
    }

    @Test
    fun `건너뛸 항목 수는 페이지 번호와 크기의 곱`() {
        assertEquals(40L, PageRequest(2, 20).offset)
    }

    @Test
    fun `전체 페이지 수는 남는 항목도 한 페이지로 셈`() {
        assertEquals(0, Page.empty<String>(PageRequest(0, 20)).totalPages)
        assertEquals(1, Page(listOf("a"), PageRequest(0, 20), 20).totalPages)
        assertEquals(2, Page(listOf("a"), PageRequest(0, 20), 21).totalPages)
    }
}
