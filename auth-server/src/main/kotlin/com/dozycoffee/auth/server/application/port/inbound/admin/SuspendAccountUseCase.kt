package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/** 관리자의 계정 정지 (api/admin.md 계정 정지, ACC-03). 직원, 파트너, system client 모두에 씁니다. */
interface SuspendAccountUseCase {
    /**
     * `ACTIVE` 계정을 `SUSPENDED`로 바꾸고 모든 refresh 세션을 폐기합니다 (`ACCOUNT_SUSPENDED`).
     *
     * @throws com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException 없는 principal
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException owner 계정(GOV-03), admin이 admin 계정 정지(GOV-02)
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException `ACTIVE`가 아님
     */
    fun suspend(command: SuspendAccountCommand)
}

/**
 * @property managerId 요청한 관리자 (직원)
 * @property reason 정지 사유. 감사 로그에 남깁니다
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class SuspendAccountCommand(
    val managerId: UUID,
    val principalId: UUID,
    val reason: String,
    val ip: String? = null,
    val userAgent: String? = null,
)
