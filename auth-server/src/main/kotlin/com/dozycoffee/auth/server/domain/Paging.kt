package com.dozycoffee.auth.server.domain

/**
 * 목록의 페이지 요청 (api/conventions.md §3). [number]는 0부터 셉니다.
 *
 * 범위 검사는 요청 검증(`VALIDATION_FAILED`)이 먼저 하고, 여기서는 쓸 수 없는 값만 막습니다.
 */
data class PageRequest(
    val number: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(number >= 0) { "페이지 번호는 0 이상이어야 합니다" }
        require(size in 1..MAX_SIZE) { "페이지 크기는 1 이상 $MAX_SIZE 이하여야 합니다" }
    }

    /** 건너뛸 항목 수. */
    val offset: Long get() = number.toLong() * size

    companion object {
        const val DEFAULT_SIZE: Int = 20
        const val MAX_SIZE: Int = 100
    }
}

/**
 * 목록의 한 페이지 (api/conventions.md §3).
 *
 * @property totalElements 조건에 맞는 전체 항목 수
 */
data class Page<out T>(
    val items: List<T>,
    val request: PageRequest,
    val totalElements: Long,
) {
    init {
        require(totalElements >= 0) { "전체 항목 수는 0 이상이어야 합니다" }
    }

    /** 전체 페이지 수. 항목이 없으면 0입니다. */
    val totalPages: Int get() = ((totalElements + request.size - 1) / request.size).toInt()

    fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), request, totalElements)

    companion object {
        fun <T> empty(request: PageRequest): Page<T> = Page(emptyList(), request, 0)
    }
}
