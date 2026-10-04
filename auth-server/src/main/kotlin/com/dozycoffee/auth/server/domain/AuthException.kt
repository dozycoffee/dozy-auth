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

/**
 * 토큰은 검증을 통과했지만 그 주체를 인증된 사용자로 볼 수 없음 (예: 계정이 없거나 비활성화됨).
 * 토큰 검증 실패와 같은 `401 UNAUTHENTICATED`로 응답합니다 (api/conventions.md §10).
 */
class UnauthenticatedException : AuthException("UNAUTHENTICATED", 401, "인증이 필요합니다.")

/**
 * 권한 없음이나 규칙상 금지된 요청 (`403 FORBIDDEN`, api/conventions.md §11).
 * 예: refresh 쿠키를 쓰는 API에 허용되지 않은 `Origin` (api/conventions.md §7).
 */
class ForbiddenException(
    message: String = "접근 권한이 없습니다.",
) : AuthException("FORBIDDEN", 403, message)
