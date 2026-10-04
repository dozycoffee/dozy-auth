package com.dozycoffee.auth.server.application.port.inbound.auth

import com.dozycoffee.auth.server.domain.credential.RawPassword

/** 초대를 수락해 비밀번호를 정하고 계정을 활성화합니다 (api/account.md 초대 수락, VER-01 `EMPLOYEE_INVITATION`). */
interface AcceptInvitationUseCase {
    /**
     * 자동 로그인하지 않습니다.
     *
     * @throws com.dozycoffee.auth.server.domain.verification.VerificationExpiredException 없거나 살아 있지 않은 초대,
     *   초대받은 계정이 `PENDING` 직원이 아닐 때, 동시 요청이 먼저 수락한 초대 (VER-04)
     * @throws com.dozycoffee.auth.server.domain.credential.PasswordPolicyViolationException 비밀번호 규칙 위반 (PWD-01, PWD-03)
     */
    fun accept(command: AcceptInvitationCommand)
}

/**
 * 초대 수락 요청.
 *
 * @property token 초대 링크의 토큰 원문
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 * @property userAgent 요청의 `User-Agent`
 */
data class AcceptInvitationCommand(
    val token: String,
    val password: RawPassword,
    val ip: String?,
    val userAgent: String?,
) {
    /** 토큰과 비밀번호는 로그 금지 값이라 가립니다 (SEC-03). */
    override fun toString(): String = "AcceptInvitationCommand(token=***, password=***)"
}
