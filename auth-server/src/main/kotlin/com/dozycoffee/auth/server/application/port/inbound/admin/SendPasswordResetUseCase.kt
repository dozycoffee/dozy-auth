package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/** 관리자의 비밀번호 재설정 메일 발송 (api/admin.md 비밀번호 재설정 메일 발송, PWD-05, VER-01 `PASSWORD_RESET`, VER-03). */
interface SendPasswordResetUseCase {
    /**
     * 이전 재설정 토큰을 무효화하고 새 `PASSWORD_RESET`을 발급하며, 메일은 커밋 후 보냅니다.
     * 비밀번호와 세션은 바꾸지 않습니다.
     *
     * @throws com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException 없는 principal
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException admin이 owner·admin 계정에 요청 (GOV-02)
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException `ACTIVE`가 아님, system client
     */
    fun sendPasswordReset(command: SendPasswordResetCommand)
}

/**
 * @property managerId 요청한 관리자 (직원)
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class SendPasswordResetCommand(
    val managerId: UUID,
    val principalId: UUID,
    val ip: String? = null,
    val userAgent: String? = null,
)
