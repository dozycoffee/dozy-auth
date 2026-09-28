package com.dozycoffee.auth.starter

import io.micrometer.tracing.Tracer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.util.ClassUtils
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 401·403 응답 (starter.md §5, api/conventions.md §4). RFC 9457 Problem Details 형식입니다.
 *
 * 실패 이유는 응답에 넣지 않고 debug 로그에만 남깁니다. 토큰 원문은 어디에도 남기지 않습니다 (SEC-03).
 */
internal class DozyAuthenticationEntryPoint(
    private val traceIds: DozyTraceIds,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        log.debug("토큰 인증 실패: {}", authException.message)
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        writeProblem(request, response, traceIds, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Unauthenticated")
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DozyAuthenticationEntryPoint::class.java)
    }
}

internal class DozyAccessDeniedHandler(
    private val traceIds: DozyTraceIds,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        writeProblem(request, response, traceIds, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Forbidden")
    }
}

private fun writeProblem(
    request: HttpServletRequest,
    response: HttpServletResponse,
    traceIds: DozyTraceIds,
    status: Int,
    code: String,
    title: String,
) {
    val traceId = traceIds.resolve(request)
    val body =
        listOf(
            "type" to json("https://docs.dozycoffee.com/errors/${code.lowercase().replace('_', '-')}"),
            "title" to json(title),
            "status" to status.toString(),
            "instance" to json(request.requestURI),
            "code" to json(code),
            "traceId" to json(traceId),
        ).joinToString(",", "{", "}") { (name, value) -> "\"$name\":$value" }

    response.status = status
    response.setHeader(DozyTraceIds.HEADER, traceId)
    response.contentType = "application/problem+json"
    response.characterEncoding = Charsets.UTF_8.name()
    response.writer.write(body)
}

/** JSON 문자열 값. Jackson 버전에 묶이지 않으려고 직접 씁니다 (값은 고정 문자열, 요청 경로, trace id뿐). */
private fun json(value: String): String =
    buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

/**
 * 에러 응답의 `traceId`. Micrometer Tracing의 현재 trace id → 요청의 `X-Trace-Id` → 새로 만든 값 순서로 씁니다.
 */
internal class DozyTraceIds(
    private val beanFactory: BeanFactory,
    private val random: SecureRandom = SecureRandom(),
) {
    fun resolve(request: HttpServletRequest): String =
        currentTraceId()
            ?: request.getHeader(HEADER)?.takeIf { INCOMING_PATTERN.matches(it) }
            ?: ByteArray(16).also(random::nextBytes).let(HexFormat.of()::formatHex)

    private fun currentTraceId(): String? {
        if (!TRACING_PRESENT) return null
        // Tracer 클래스가 없을 때 이 줄이 실행되지 않도록 위에서 먼저 확인합니다
        return beanFactory
            .getBeanProvider(Tracer::class.java)
            .ifAvailable
            ?.currentSpan()
            ?.context()
            ?.traceId()
    }

    companion object {
        const val HEADER: String = "X-Trace-Id"

        /** 헤더 주입을 막기 위해 받아 쓰는 값의 형식을 제한합니다. */
        private val INCOMING_PATTERN = Regex("[A-Za-z0-9-]{1,64}")

        private val TRACING_PRESENT = ClassUtils.isPresent("io.micrometer.tracing.Tracer", DozyTraceIds::class.java.classLoader)
    }
}
