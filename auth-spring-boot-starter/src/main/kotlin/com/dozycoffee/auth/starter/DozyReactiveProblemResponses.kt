package com.dozycoffee.auth.starter

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.core.ResolvableType
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebFlux의 401·403 응답. 본문은 [DozyReactiveProblemWriter]가 씁니다.
 *
 * 실패 이유는 응답에 넣지 않고 debug 로그에만 남깁니다. 토큰 원문은 어디에도 남기지 않습니다 (SEC-03).
 */
internal class DozyServerAuthenticationEntryPoint(
    private val writer: DozyReactiveProblemWriter,
) : ServerAuthenticationEntryPoint {
    override fun commence(
        exchange: ServerWebExchange,
        ex: AuthenticationException,
    ): Mono<Void> {
        log.debug("토큰 인증 실패: {}", ex.message)
        exchange.response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        return writer.write(exchange, DozyProblem.UNAUTHENTICATED)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DozyServerAuthenticationEntryPoint::class.java)
    }
}

internal class DozyServerAccessDeniedHandler(
    private val writer: DozyReactiveProblemWriter,
) : ServerAccessDeniedHandler {
    override fun handle(
        exchange: ServerWebExchange,
        denied: AccessDeniedException,
    ): Mono<Void> = writer.write(exchange, DozyProblem.FORBIDDEN)
}

/**
 * 401·403 본문을 서비스의 서버 codec(`ServerCodecConfigurer`의 writer)으로 `application/problem+json`으로 씁니다.
 *
 * 컨트롤러 응답에 쓰는 것과 같은 codec이라 서비스의 `WebFluxConfigurer`, `CodecCustomizer`, Jackson 설정이 그대로 적용됩니다.
 * `ProblemDetail`을 쓸 수 있는 writer가 없으면 [DozyProblem.body]를 직접 씁니다.
 */
internal class DozyReactiveProblemWriter(
    private val traceIds: DozyTraceIds,
    private val writers: () -> List<HttpMessageWriter<*>>,
) {
    private val fallbackLogged = AtomicBoolean()

    fun write(
        exchange: ServerWebExchange,
        problem: DozyProblem,
    ): Mono<Void> {
        val response = exchange.response
        val traceId =
            traceIds.resolve(response.headers.getFirst(DozyTraceIds.HEADER), exchange.request.headers.getFirst(DozyTraceIds.HEADER))
        response.statusCode = HttpStatus.valueOf(problem.status)
        response.headers.set(DozyTraceIds.HEADER, traceId)
        val instance = exchange.request.path.value()

        val writer = writers().firstOrNull { it.canWrite(PROBLEM_DETAIL_TYPE, DozyProblem.MEDIA_TYPE) }
        if (writer == null) {
            if (fallbackLogged.compareAndSet(false, true)) {
                log.warn("ProblemDetail을 {}로 쓸 HttpMessageWriter가 없어 401·403 본문을 직접 씁니다", DozyProblem.CONTENT_TYPE)
            }
            response.headers.contentType = MediaType.parseMediaType("${DozyProblem.CONTENT_TYPE};charset=UTF-8")
            val body = response.bufferFactory().wrap(problem.body(instance, traceId).toByteArray(Charsets.UTF_8))
            return response.writeWith(Mono.just(body))
        }
        @Suppress("UNCHECKED_CAST")
        return (writer as HttpMessageWriter<ProblemDetail>).write(
            Mono.just(problem.problemDetail(instance, traceId)),
            PROBLEM_DETAIL_TYPE,
            DozyProblem.MEDIA_TYPE,
            response,
            emptyMap(),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DozyReactiveProblemWriter::class.java)
        private val PROBLEM_DETAIL_TYPE = ResolvableType.forClass(ProblemDetail::class.java)

        /** WebFlux가 쓰는 codec을 요청 때마다 찾습니다. */
        fun create(beanFactory: BeanFactory): DozyReactiveProblemWriter =
            DozyReactiveProblemWriter(DozyTraceIds(beanFactory)) {
                beanFactory
                    .getBeanProvider(ServerCodecConfigurer::class.java)
                    .ifUnique
                    ?.writers
                    .orEmpty()
            }
    }
}
