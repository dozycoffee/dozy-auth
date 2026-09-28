package com.dozycoffee.auth.starter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * Spring MVC의 401·403 응답. 본문은 [DozyProblem]이 만듭니다.
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
        write(request, response, traceIds, DozyProblem.UNAUTHENTICATED)
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
    ) = write(request, response, traceIds, DozyProblem.FORBIDDEN)
}

private fun write(
    request: HttpServletRequest,
    response: HttpServletResponse,
    traceIds: DozyTraceIds,
    problem: DozyProblem,
) {
    val traceId = traceIds.resolve(request.getHeader(DozyTraceIds.HEADER))
    response.status = problem.status
    response.setHeader(DozyTraceIds.HEADER, traceId)
    response.contentType = DozyProblem.CONTENT_TYPE
    response.characterEncoding = Charsets.UTF_8.name()
    response.writer.write(problem.body(request.requestURI, traceId))
}
