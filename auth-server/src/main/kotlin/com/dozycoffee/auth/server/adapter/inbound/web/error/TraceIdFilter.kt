package com.dozycoffee.auth.server.adapter.inbound.web.error

import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 모든 응답에 `X-Trace-Id`를 넣습니다 (api/conventions.md §9). 에러 응답의 `traceId`, 로그의 `traceId`와 같은 값입니다.
 *
 * 값은 아래 순서로 처음 있는 것을 씁니다 (스타터의 `traceId`와 같은 순서, starter.md §5).
 * 1. Micrometer Tracing의 현재 trace id. 요청에 W3C `traceparent`가 있으면 그 trace를 이어 갑니다 (configuration.md §10)
 * 2. 요청의 `X-Trace-Id` (영문·숫자·하이픈 64자 이내)
 * 3. 새로 만든 값
 *
 * 서버에서는 Micrometer Tracing이 늘 켜져 있어 1을 씁니다. 2, 3은 추적이 없는 웹 슬라이스 테스트나 추적을 끈 설정용이며,
 * 그때는 로그와 값이 맞도록 이 필터가 MDC `traceId`를 넣습니다.
 *
 * Spring Boot의 HTTP 관측 필터(trace를 시작함) 바로 뒤, Spring Security 필터보다 먼저 실행되어 401·403 응답도 같은 값을 씁니다.
 */
@Component
@Order(TraceIdFilter.ORDER)
class TraceIdFilter(
    private val tracer: ObjectProvider<Tracer>,
) : OncePerRequestFilter() {
    private val random = SecureRandom()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traced = currentTraceId()
        val traceId = traced ?: request.getHeader(HEADER)?.takeIf(ACCEPTED::matches) ?: newTraceId()
        request.setAttribute(ATTRIBUTE, traceId)
        response.setHeader(HEADER, traceId)
        if (traced != null) {
            filterChain.doFilter(request, response)
            return
        }
        MDC.put(MDC_KEY, traceId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    private fun currentTraceId(): String? =
        tracer.ifAvailable
            ?.currentSpan()
            ?.context()
            ?.traceId()
            ?.takeIf { it.isNotBlank() }

    private fun newTraceId(): String = HexFormat.of().formatHex(ByteArray(TRACE_ID_BYTES).also(random::nextBytes))

    companion object {
        const val HEADER = "X-Trace-Id"

        /** Spring Boot의 HTTP 관측 필터(`ServerHttpObservationFilter`, `HIGHEST_PRECEDENCE + 1`) 바로 뒤. */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 2

        /** Micrometer Tracing이 로그에 trace id를 넣는 MDC 키와 같습니다. */
        private const val MDC_KEY = "traceId"
        private const val ATTRIBUTE = "com.dozycoffee.auth.server.traceId"
        private const val TRACE_ID_BYTES = 16
        private val ACCEPTED = Regex("[A-Za-z0-9-]{1,64}")

        /** 필터가 정한 이 요청의 trace id. 필터를 거치지 않았으면 `null`입니다. */
        fun of(request: HttpServletRequest): String? = request.getAttribute(ATTRIBUTE) as? String
    }
}
