package com.dozycoffee.auth.server.application.port.inbound.auth

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.credential.RawPassword
import java.util.UUID

/** 로그인한 사용자가 현재 비밀번호를 확인하고 비밀번호를 바꿉니다 (api/auth.md 비밀번호 변경, PWD-06, PWD-08). */
interface ChangePasswordUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.TooManyAttemptsException `policy.rate-limit-password-confirm`을 넘었을 때
     * @throws com.dozycoffee.auth.server.domain.UnauthenticatedException 계정이 없거나 비활성화됐을 때
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException 계정이 `ACTIVE`가 아닐 때
     * @throws com.dozycoffee.auth.server.domain.credential.CurrentPasswordMismatchException 현재 비밀번호가 틀렸을 때 (PWD-08)
     * @throws com.dozycoffee.auth.server.domain.credential.PasswordPolicyViolationException 새 비밀번호가 PWD-01, PWD-03을 어겼을 때
     */
    fun changePassword(command: ChangePasswordCommand)
}

/**
 * 비밀번호 변경 요청.
 *
 * @property principal 검증한 토큰의 주체 (`employee`만. 파트너는 partner realm 작업에서 추가)
 * @property realm 요청 경로의 realm. 감사 로그에 남깁니다
 * @property currentSessionId 토큰의 `sid`. 이 세션만 남기고 나머지를 폐기합니다 (PWD-06). 없으면 모든 세션을 폐기합니다
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 * @property userAgent 요청의 `User-Agent`
 */
data class ChangePasswordCommand(
    val principal: PrincipalKey,
    val realm: Realm,
    val currentSessionId: UUID?,
    val currentPassword: RawPassword,
    val newPassword: RawPassword,
    val ip: String?,
    val userAgent: String?,
) {
    /** 비밀번호는 로그 금지 값이라 가립니다 (SEC-03). */
    override fun toString(): String = "ChangePasswordCommand(principal=$principal, realm=$realm, currentPassword=***, newPassword=***)"
}
