package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/** 관리자의 정지 해제 (api/admin.md 정지 해제, ACC-01). 직원, 파트너, system client 모두에 씁니다. */
interface ReactivateAccountUseCase {
    /**
     * `SUSPENDED` 계정을 `ACTIVE`로 바꿉니다. 정지할 때 폐기한 세션은 되살리지 않으므로 다시 로그인해야 합니다.
     *
     * @throws com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException 없는 principal
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException admin이 owner·admin 계정 해제 (GOV-02)
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException `SUSPENDED`가 아님
     */
    fun reactivate(command: ReactivateAccountCommand)
}

/**
 * @property managerId 요청한 관리자 (직원)
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class ReactivateAccountCommand(
    val managerId: UUID,
    val principalId: UUID,
    val ip: String? = null,
    val userAgent: String? = null,
)
