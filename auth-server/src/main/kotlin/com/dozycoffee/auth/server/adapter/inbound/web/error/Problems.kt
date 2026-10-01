package com.dozycoffee.auth.server.adapter.inbound.web.error

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI

/** 에러 응답 본문 (api/conventions.md §4). RFC 9457 Problem Details에 `code`와 `traceId`를 더합니다. */
internal object Problems {
    private const val TYPE_BASE = "https://docs.dozycoffee.com/errors/"

    fun create(
        status: HttpStatusCode,
        code: String,
        detail: String?,
        request: HttpServletRequest,
    ): ProblemDetail = enrich(ProblemDetail.forStatusAndDetail(status, detail), code, request)

    /** 이미 만들어진 [problem](프레임워크가 만든 것 포함)에 `type`, `title`, `instance`, `code`, `traceId`를 채웁니다. */
    fun enrich(
        problem: ProblemDetail,
        code: String,
        request: HttpServletRequest,
    ): ProblemDetail {
        problem.type = URI.create(TYPE_BASE + code.lowercase().replace('_', '-'))
        problem.title = code.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        runCatching { URI(request.requestURI) }.onSuccess { problem.instance = it }
        problem.setProperty("code", code)
        TraceIdFilter.of(request)?.let { problem.setProperty("traceId", it) }
        return problem
    }

    /** 프레임워크가 만든 에러(404, 405 등)에 쓸 code. 명세의 에러 코드 중 상태에 맞는 것을 고릅니다. */
    fun codeFor(status: HttpStatusCode): String =
        when {
            status.value() == HttpStatus.BAD_REQUEST.value() -> "VALIDATION_FAILED"
            status.value() == HttpStatus.NOT_FOUND.value() -> "NOT_FOUND"
            status.is5xxServerError -> "INTERNAL_ERROR"
            else -> HttpStatus.resolve(status.value())?.name ?: "VALIDATION_FAILED"
        }
}
