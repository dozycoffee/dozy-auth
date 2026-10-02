package com.dozycoffee.auth.server.domain.account

import com.dozycoffee.auth.server.domain.AuthException

/** 현재 계정 상태에서 허용되지 않는 전이나 작업 (ACC-01). */
class InvalidAccountStateException : AuthException("INVALID_STATE", 409, "현재 계정 상태에서 할 수 없는 작업입니다.")

/** 같은 이메일(대소문자 무시)의 계정이 이미 있음 (DOM-02). 메시지에 이메일을 넣지 않습니다 (SEC-03). */
class DuplicateEmailException : AuthException("DUPLICATE_EMAIL", 409, "이미 사용 중인 이메일입니다.")
