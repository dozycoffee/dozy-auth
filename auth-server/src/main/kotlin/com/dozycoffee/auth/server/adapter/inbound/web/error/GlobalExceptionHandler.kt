package com.dozycoffee.auth.server.adapter.inbound.web.error

import com.dozycoffee.auth.server.domain.AuthException
import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSourceResolvable
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationTrustResolverImpl
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.FieldError
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * 모든 예외를 Problem Details로 변환합니다 (api/conventions.md §4, architecture.md §9.1).
 *
 * 도메인 예외는 자신의 code·status를 쓰고, 프레임워크가 만든 4xx는 형식만 맞추며, 그 밖의 예외는 내부 정보 없이 500으로 응답합니다.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(AuthException::class)
    fun handleAuthException(
        ex: AuthException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val problem = Problems.create(HttpStatusCode.valueOf(ex.status), ex.code, ex.message, request)
        val response = ResponseEntity.status(ex.status)
        if (ex.status == HttpStatus.UNAUTHORIZED.value()) response.header(HttpHeaders.WWW_AUTHENTICATE, BEARER)
        if (ex is TooManyAttemptsException) response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds(ex).toString())
        return response.body(problem)
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(
        ex: AuthenticationException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        log.debug("인증 실패: {}", ex.message)
        return unauthenticated(request)
    }

    /** 로그인하지 않은 요청이 인가에서 막히면 403이 아니라 401입니다. */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(
        ex: AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        if (trustResolver.isAnonymous(SecurityContextHolder.getContext().authentication)) return unauthenticated(request)
        val problem = Problems.create(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.", request)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val problem = validationFailed(request)
        problem.setProperty(
            "errors",
            ex.constraintViolations.map {
                fieldError(
                    it.propertyPath.toString().substringAfterLast('.'),
                    it.constraintDescriptor.annotation.annotationClass.simpleName,
                    it.message,
                )
            },
        )
        return ResponseEntity.badRequest().body(problem)
    }

    /** 처리하지 못한 예외. 응답에는 내부 정보를 넣지 않고 로그에만 남깁니다. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        log.error("처리되지 않은 예외 traceId={}", TraceIdFilter.of(request), ex)
        val problem = Problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_ERROR_DETAIL, request)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors.map { fieldError(it.field, it.code, it.defaultMessage) }
        ex.body.setProperty("errors", errors)
        return handleExceptionInternal(ex, null, headers, status, request)
    }

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors =
            ex.parameterValidationResults.flatMap { result ->
                result.resolvableErrors.map { error ->
                    fieldError(
                        fieldName(error) ?: result.methodParameter.parameterName.orEmpty(),
                        error.codes?.lastOrNull(),
                        error.defaultMessage,
                    )
                }
            }
        ex.body.setProperty("errors", errors)
        return handleExceptionInternal(ex, null, headers, status, request)
    }

    /** 프레임워크가 만든 에러(검증 실패, 404, 405 등)도 같은 형식으로 맞춥니다. */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val servletRequest = (request as ServletWebRequest).request
        val problem = (body as? ProblemDetail) ?: (ex as? ErrorResponse)?.body ?: ProblemDetail.forStatus(statusCode)
        val code = Problems.codeFor(statusCode)
        when {
            statusCode.is5xxServerError -> {
                log.error("프레임워크 예외 traceId={}", TraceIdFilter.of(servletRequest), ex)
                problem.detail = INTERNAL_ERROR_DETAIL
            }
            code == "VALIDATION_FAILED" -> problem.detail = VALIDATION_FAILED_DETAIL
        }
        return ResponseEntity.status(statusCode).headers(headers).body(Problems.enrich(problem, code, servletRequest))
    }

    private fun unauthenticated(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = Problems.create(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "인증이 필요합니다.", request)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, BEARER).body(problem)
    }

    private fun validationFailed(request: HttpServletRequest): ProblemDetail =
        Problems.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", VALIDATION_FAILED_DETAIL, request)

    private fun fieldError(
        field: String,
        code: String?,
        message: String?,
    ): Map<String, String?> = mapOf("field" to field, "code" to code, "message" to message)

    private fun fieldName(error: MessageSourceResolvable): String? = (error as? FieldError)?.field

    private fun retryAfterSeconds(ex: TooManyAttemptsException): Long = maxOf(1L, (ex.retryAfter.toMillis() + MILLIS - 1) / MILLIS)

    private companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
        private val trustResolver = AuthenticationTrustResolverImpl()
        private const val BEARER = "Bearer"
        private const val MILLIS = 1000L
        private const val VALIDATION_FAILED_DETAIL = "요청 값이 올바르지 않습니다."
        private const val INTERNAL_ERROR_DETAIL = "서버 내부 오류가 발생했습니다."
    }
}
