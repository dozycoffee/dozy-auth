package com.dozycoffee.auth.server.adapter.inbound.web

import com.dozycoffee.auth.server.domain.Page

/** 페이지를 나누는 목록의 응답 형식 (api/conventions.md §3). */
data class PageResponse<T>(
    val items: List<T>,
    val page: PageInfo,
) {
    data class PageInfo(
        val number: Int,
        val size: Int,
        val totalElements: Long,
        val totalPages: Int,
    )

    companion object {
        fun <S, T> of(
            page: Page<S>,
            transform: (S) -> T,
        ): PageResponse<T> =
            PageResponse(
                items = page.items.map(transform),
                page = PageInfo(page.request.number, page.request.size, page.totalElements, page.totalPages),
            )
    }
}
