package com.dozycoffee.auth.server.domain.account

import com.dozycoffee.auth.server.domain.AuthException

/** 현재 계정 상태에서 허용되지 않는 전이나 작업 (ACC-01). */
class InvalidAccountStateException : AuthException("INVALID_STATE", 409, "현재 계정 상태에서 할 수 없는 작업입니다.")

/** 같은 이메일(대소문자 무시)의 계정이 이미 있음 (DOM-02). 메시지에 이메일을 넣지 않습니다 (SEC-03). */
class DuplicateEmailException : AuthException("DUPLICATE_EMAIL", 409, "이미 사용 중인 이메일입니다.")

/** LGN-03 비밀번호는 맞았지만 이메일 인증 전(`PENDING`)인 계정의 로그인. */
class EmailNotVerifiedException : AuthException("EMAIL_NOT_VERIFIED", 403, "이메일 인증이 필요합니다.")

/** LGN-03 비밀번호는 맞았지만 정지된(`SUSPENDED`) 계정의 로그인. */
class AccountSuspendedException : AuthException("ACCOUNT_SUSPENDED", 403, "정지된 계정입니다.")

/** 없는 직원, 직원이 아닌 principal (api/admin.md 직원 상세). 어느 경우인지 구분하지 않습니다. */
class EmployeeNotFoundException : AuthException("NOT_FOUND", 404, "직원을 찾을 수 없습니다.")

/** 없는 principal (관리 API의 `/admin/principals/{principalId}/...`). */
class PrincipalNotFoundException : AuthException("NOT_FOUND", 404, "principal을 찾을 수 없습니다.")
