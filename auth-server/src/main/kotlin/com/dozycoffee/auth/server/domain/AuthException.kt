package com.dozycoffee.auth.server.domain

import java.time.Duration

/**
 * 규칙 위반을 나타내는 도메인 예외의 기반 클래스 (architecture.md §9.1).
 *
 * [code]는 api/conventions.md §11의 에러 코드와 같은 이름이고 [status]는 HTTP 상태 숫자입니다.
 * 도메인은 Spring에 의존하지 않으므로 `HttpStatus`를 쓰지 않습니다. [message]는 응답의 `detail`이 되므로
 * 민감정보(SEC-03)를 넣지 않습니다.
 */
abstract class AuthException(
    val code: String,
    val status: Int,
    message: String,
) : RuntimeException(message)

/** 요청 제한 초과나 계정 잠금. [retryAfter] 뒤에 다시 시도할 수 있고 `Retry-After` 헤더가 됩니다. */
class TooManyAttemptsException(
    val retryAfter: Duration,
    message: String = "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
) : AuthException("TOO_MANY_ATTEMPTS", 429, message)
