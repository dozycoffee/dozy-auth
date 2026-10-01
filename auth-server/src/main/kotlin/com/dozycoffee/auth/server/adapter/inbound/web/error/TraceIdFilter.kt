package com.dozycoffee.auth.server.adapter.inbound.web.error

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 모든 응답에 `X-Trace-Id`를 넣습니다 (api/conventions.md §9). 에러 응답의 `traceId`와 같은 값입니다.
 *
 * 요청의 `X-Trace-Id`가 영문·숫자·하이픈 64자 이내면 그대로 쓰고, 아니면 새로 만듭니다.
 * Spring Security 필터보다 먼저 실행되어 401·403 응답도 같은 값을 씁니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    private val random = SecureRandom()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = request.getHeader(HEADER)?.takeIf(ACCEPTED::matches) ?: newTraceId()
        request.setAttribute(ATTRIBUTE, traceId)
        response.setHeader(HEADER, traceId)
        filterChain.doFilter(request, response)
    }

    private fun newTraceId(): String = HexFormat.of().formatHex(ByteArray(TRACE_ID_BYTES).also(random::nextBytes))

    companion object {
        const val HEADER = "X-Trace-Id"
        private const val ATTRIBUTE = "com.dozycoffee.auth.server.traceId"
        private const val TRACE_ID_BYTES = 16
        private val ACCEPTED = Regex("[A-Za-z0-9-]{1,64}")

        /** 필터가 정한 이 요청의 trace id. 필터를 거치지 않았으면 `null`입니다. */
        fun of(request: HttpServletRequest): String? = request.getAttribute(ATTRIBUTE) as? String
    }
}
