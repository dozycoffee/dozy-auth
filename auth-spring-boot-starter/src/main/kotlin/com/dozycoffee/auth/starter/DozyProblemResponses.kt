package com.dozycoffee.auth.starter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.util.ClassUtils
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spring MVC의 401·403 응답. 본문은 [DozyServletProblemWriter]가 씁니다.
 *
 * 실패 이유는 응답에 넣지 않고 debug 로그에만 남깁니다. 토큰 원문은 어디에도 남기지 않습니다 (SEC-03).
 */
internal class DozyAuthenticationEntryPoint(
    private val writer: DozyServletProblemWriter,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        log.debug("토큰 인증 실패: {}", authException.message)
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        writer.write(request, response, DozyProblem.UNAUTHENTICATED)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DozyAuthenticationEntryPoint::class.java)
    }
}

internal class DozyAccessDeniedHandler(
    private val writer: DozyServletProblemWriter,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) = writer.write(request, response, DozyProblem.FORBIDDEN)
}

/**
 * 401·403 본문을 서비스의 `HttpMessageConverter`로 `application/problem+json`으로 씁니다.
 *
 * 변환기는 Spring MVC가 컨트롤러 응답에 쓰는 것(`RequestMappingHandlerAdapter`)이라 서비스의 `WebMvcConfigurer`, Jackson 설정이
 * 그대로 적용됩니다. `ProblemDetail`을 쓸 수 있는 변환기가 없으면 [DozyProblem.body]를 직접 씁니다.
 */
internal class DozyServletProblemWriter(
    private val traceIds: DozyTraceIds,
    private val converters: () -> List<HttpMessageConverter<*>>,
) {
    private val fallbackLogged = AtomicBoolean()

    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        problem: DozyProblem,
    ) {
        val traceId = traceIds.resolve(response.getHeader(DozyTraceIds.HEADER), request.getHeader(DozyTraceIds.HEADER))
        response.status = problem.status
        response.setHeader(DozyTraceIds.HEADER, traceId)

        val converter = converters().firstOrNull { it.canWrite(ProblemDetail::class.java, DozyProblem.MEDIA_TYPE) }
        if (converter == null) {
            if (fallbackLogged.compareAndSet(false, true)) {
                log.warn("ProblemDetail을 {}로 쓸 HttpMessageConverter가 없어 401·403 본문을 직접 씁니다", DozyProblem.CONTENT_TYPE)
            }
            response.contentType = DozyProblem.CONTENT_TYPE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write(problem.body(request.requestURI, traceId))
            return
        }
        @Suppress("UNCHECKED_CAST")
        (converter as HttpMessageConverter<Any>).write(
            problem.problemDetail(request.requestURI, traceId),
            DozyProblem.MEDIA_TYPE,
            ServletServerHttpResponse(response),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(DozyServletProblemWriter::class.java)

        private val WEB_MVC_PRESENT =
            ClassUtils.isPresent(
                "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter",
                DozyServletProblemWriter::class.java.classLoader,
            )

        /** Spring MVC가 쓰는 변환기를 요청 때마다 찾습니다. Spring MVC가 없는 servlet 앱(Jersey 등)이면 빈 목록입니다. */
        fun create(beanFactory: BeanFactory): DozyServletProblemWriter =
            DozyServletProblemWriter(DozyTraceIds(beanFactory)) {
                if (WEB_MVC_PRESENT) {
                    // RequestMappingHandlerAdapter 클래스가 없을 때 이 줄이 실행되지 않도록 위에서 먼저 확인합니다
                    beanFactory
                        .getBeanProvider(RequestMappingHandlerAdapter::class.java)
                        .ifUnique
                        ?.messageConverters
                        .orEmpty()
                } else {
                    emptyList()
                }
            }
    }
}
