package com.dozycoffee.auth.starter

import io.micrometer.tracing.Tracer
import org.springframework.beans.factory.BeanFactory
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.util.ClassUtils
import java.net.URI
import java.net.URISyntaxException
import java.security.SecureRandom
import java.util.HexFormat

/**
 * 401·403 응답 본문 (starter.md §5, api/conventions.md §4). RFC 9457 Problem Details 형식이며 Spring MVC와 WebFlux가 함께 씁니다.
 *
 * 본문은 [problemDetail]로 만들어 서비스의 메시지 변환기(WebFlux는 codec)로 씁니다. 서비스가 직렬화 설정을 바꿔도 서비스 자신의
 * 에러 응답과 같은 방식으로 쓰기 위해서입니다. `ProblemDetail`을 쓸 수 있는 변환기가 없으면(Jackson이 없는 서비스 등) [body]의
 * JSON을 직접 씁니다. 두 방식의 필드와 값은 같습니다.
 */
internal enum class DozyProblem(
    val status: Int,
    val code: String,
    val title: String,
) {
    UNAUTHENTICATED(401, "UNAUTHENTICATED", "Unauthenticated"),
    FORBIDDEN(403, "FORBIDDEN", "Forbidden"),
    ;

    private val type: String get() = "https://docs.dozycoffee.com/errors/${code.lowercase().replace('_', '-')}"

    /** 서비스의 변환기로 쓸 본문. `properties`(`code`, `traceId`)는 변환기가 최상위 필드로 펼칩니다. `detail`은 비워 둡니다. */
    fun problemDetail(
        instance: String,
        traceId: String,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).also {
            it.type = URI.create(type)
            it.title = title
            it.instance = instanceUri(instance)
            it.setProperty("code", code)
            it.setProperty("traceId", traceId)
        }

    /** 변환기를 쓸 수 없을 때의 본문. [problemDetail]과 필드와 값이 같습니다. */
    fun body(
        instance: String,
        traceId: String,
    ): String =
        listOf(
            "type" to json(type),
            "title" to json(title),
            "status" to status.toString(),
            "instance" to json(instance),
            "code" to json(code),
            "traceId" to json(traceId),
        ).joinToString(",", "{", "}") { (name, value) -> "\"$name\":$value" }

    companion object {
        const val CONTENT_TYPE: String = "application/problem+json"
        val MEDIA_TYPE: MediaType = MediaType.APPLICATION_PROBLEM_JSON
    }
}

/** 요청 경로는 보통 이미 인코딩된 값이라 그대로 URI가 됩니다. 그렇지 않은 문자가 있으면 인코딩합니다. */
private fun instanceUri(path: String): URI =
    try {
        URI(path)
    } catch (_: URISyntaxException) {
        URI(null, null, path, null)
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
 * 에러 응답의 `traceId`와 응답 헤더 `X-Trace-Id`. 아래 순서로 처음 있는 값을 씁니다 (starter.md §5).
 *
 * 1. Micrometer Tracing의 현재 trace id
 * 2. 서비스의 필터가 이미 응답에 붙인 `X-Trace-Id`
 * 3. 요청의 `X-Trace-Id` (형식 제한)
 * 4. 새로 만든 값
 */
internal class DozyTraceIds(
    private val beanFactory: BeanFactory,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * @param outgoing 응답에 이미 붙은 `X-Trace-Id`. 서비스가 정한 값이므로 형식을 제한하지 않습니다.
     * @param incoming 요청의 `X-Trace-Id`
     */
    fun resolve(
        outgoing: String?,
        incoming: String?,
    ): String =
        currentTraceId()
            ?: outgoing?.takeIf { it.isNotBlank() }
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
