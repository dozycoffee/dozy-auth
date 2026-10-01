package com.dozycoffee.auth.server.domain.credential

import com.dozycoffee.auth.server.domain.AuthException

/** 로그인 실패 (LGN-02). 없는 계정, 비밀번호 불일치, 비활성화된 계정을 구분하지 않습니다. */
class InvalidCredentialsException : AuthException("INVALID_CREDENTIALS", 401, "이메일 또는 비밀번호가 올바르지 않습니다.")
