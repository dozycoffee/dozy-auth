package com.dozycoffee.auth.server.domain.token

import com.dozycoffee.auth.server.domain.AuthException

/** 토큰 발급 요청의 주체나 role이 형식·조합 규칙(DOM-01, DOM-03, DOM-04)을 어김 (`400 VALIDATION_FAILED`). */
class InvalidTokenRequestException(
    message: String,
) : AuthException("VALIDATION_FAILED", 400, message)
