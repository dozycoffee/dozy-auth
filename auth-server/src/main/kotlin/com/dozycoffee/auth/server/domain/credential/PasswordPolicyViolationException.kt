package com.dozycoffee.auth.server.domain.credential

import com.dozycoffee.auth.server.domain.AuthException

/** 새 비밀번호가 비밀번호 규칙(PWD-01, PWD-03)을 어김. 메시지에 비밀번호와 이메일을 넣지 않습니다 (SEC-03). */
class PasswordPolicyViolationException(
    message: String,
) : AuthException("VALIDATION_FAILED", 400, message)
