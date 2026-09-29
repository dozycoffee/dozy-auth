package com.dozycoffee.auth.starter

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * WebFlux의 401·403 응답. 본문은 [DozyProblem]이 만듭니다.
 *
 * 실패 이유는 응답에 넣지 않고 debug 로그에만 남깁니다. 토큰 원문은 어디에도 남기지 않습니다 (SEC-03).
 */
internal class DozyServerAuthenticationEntryPoint(
    private val traceIds: DozyTraceIds,
) : ServerAuthenticationEntryPoint {
    override fun commence(
        exchange: ServerWebExchange,
        ex: AuthenticationException,
    ): Mono<Void> {
        log.debug("토큰 인증 실패: {}", ex.message)
        exchange.response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        return write(exchange, traceIds, DozyProblem.UNAUTHENTICATED)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DozyServerAuthenticationEntryPoint::class.java)
    }
}

internal class DozyServerAccessDeniedHandler(
    private val traceIds: DozyTraceIds,
) : ServerAccessDeniedHandler {
    override fun handle(
        exchange: ServerWebExchange,
        denied: AccessDeniedException,
    ): Mono<Void> = write(exchange, traceIds, DozyProblem.FORBIDDEN)
}

private fun write(
    exchange: ServerWebExchange,
    traceIds: DozyTraceIds,
    problem: DozyProblem,
): Mono<Void> {
    val traceId = traceIds.resolve(exchange.request.headers.getFirst(DozyTraceIds.HEADER))
    val response = exchange.response
    response.statusCode = HttpStatus.valueOf(problem.status)
    response.headers.set(DozyTraceIds.HEADER, traceId)
    response.headers.contentType = MediaType.parseMediaType("${DozyProblem.CONTENT_TYPE};charset=UTF-8")
    val body = response.bufferFactory().wrap(problem.body(exchange.request.path.value(), traceId).toByteArray(Charsets.UTF_8))
    return response.writeWith(Mono.just(body))
}
