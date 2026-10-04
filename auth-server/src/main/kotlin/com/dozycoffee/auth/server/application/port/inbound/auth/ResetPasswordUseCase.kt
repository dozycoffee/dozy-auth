package com.dozycoffee.auth.server.application.port.inbound.auth

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.credential.RawPassword

/** 재설정 메일의 토큰으로 비밀번호를 바꿉니다 (api/account.md 비밀번호 재설정, PWD-07, VER-04). */
interface ResetPasswordUseCase {
    /**
     * 자동 로그인하지 않습니다.
     *
     * @throws com.dozycoffee.auth.server.domain.verification.VerificationExpiredException 없거나 살아 있지 않은 토큰, 토큰의 계정이
     *   경로의 realm이 아니거나 `ACTIVE`가 아닐 때, 동시 요청이 먼저 쓴 토큰 (VER-04)
     * @throws com.dozycoffee.auth.server.domain.credential.PasswordPolicyViolationException 새 비밀번호 규칙 위반 (PWD-01, PWD-03).
     *   토큰은 소비하지 않습니다
     */
    fun resetPassword(command: ResetPasswordCommand)
}

/**
 * 비밀번호 재설정 요청.
 *
 * @property realm 요청 경로의 realm
 * @property token 재설정 링크의 토큰 원문
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 * @property userAgent 요청의 `User-Agent`
 */
data class ResetPasswordCommand(
    val realm: Realm,
    val token: String,
    val newPassword: RawPassword,
    val ip: String?,
    val userAgent: String?,
) {
    /** 토큰과 비밀번호는 로그 금지 값이라 가립니다 (SEC-03). */
    override fun toString(): String = "ResetPasswordCommand(realm=$realm, token=***, newPassword=***)"
}
