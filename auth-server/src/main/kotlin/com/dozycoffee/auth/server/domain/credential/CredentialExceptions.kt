package com.dozycoffee.auth.server.domain.credential

import com.dozycoffee.auth.server.domain.AuthException

/** 로그인 실패 (LGN-02). 없는 계정, 비밀번호 불일치, 비활성화된 계정을 구분하지 않습니다. */
class InvalidCredentialsException : AuthException("INVALID_CREDENTIALS", 401, "이메일 또는 비밀번호가 올바르지 않습니다.")

/** 새 비밀번호가 비밀번호 규칙(PWD-01, PWD-03)을 어김. 메시지에 비밀번호와 이메일을 넣지 않습니다 (SEC-03). */
class PasswordPolicyViolationException(
    message: String,
) : AuthException("VALIDATION_FAILED", 400, message)

/** PWD-08 본인 확인용 현재 비밀번호가 틀림. 앱은 `401`을 받으면 로그아웃하므로 `400`으로 응답합니다. */
class CurrentPasswordMismatchException : AuthException("CURRENT_PASSWORD_MISMATCH", 400, "현재 비밀번호가 올바르지 않습니다.")
