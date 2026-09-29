package com.dozycoffee.auth.starter

import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.BeanFactory
import org.springframework.util.ClassUtils
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 401·403 응답 본문 (starter.md §5, api/conventions.md §4). RFC 9457 Problem Details 형식이며 Spring MVC와 WebFlux가 함께 씁니다.
 *
 * 서비스의 Jackson 버전(2·3)에 묶이지 않으려고 JSON을 직접 씁니다. 값은 고정 문자열, 요청 경로, trace id뿐입니다.
 */
internal enum class DozyProblem(
    val status: Int,
    val code: String,
    val title: String,
) {
    UNAUTHENTICATED(401, "UNAUTHENTICATED", "Unauthenticated"),
    FORBIDDEN(403, "FORBIDDEN", "Forbidden"),
    ;

    fun body(
        instance: String,
        traceId: String,
    ): String =
        listOf(
            "type" to json("https://docs.dozycoffee.com/errors/${code.lowercase().replace('_', '-')}"),
            "title" to json(title),
            "status" to status.toString(),
            "instance" to json(instance),
            "code" to json(code),
            "traceId" to json(traceId),
        ).joinToString(",", "{", "}") { (name, value) -> "\"$name\":$value" }

    companion object {
        const val CONTENT_TYPE: String = "application/problem+json"
    }
}

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
    fun resolve(incoming: String?): String =
        currentTraceId()
            ?: incoming?.takeIf { INCOMING_PATTERN.matches(it) }
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
