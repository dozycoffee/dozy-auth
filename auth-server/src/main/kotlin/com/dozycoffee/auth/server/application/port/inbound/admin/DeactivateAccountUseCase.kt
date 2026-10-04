package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/**
 * 관리자의 계정 비활성화 (api/admin.md 계정 비활성화, ACC-04, CLI-04). 직원, 파트너, system client 모두에 쓰며 system client 폐기도 이것으로 합니다.
 */
interface DeactivateAccountUseCase {
    /**
     * `PENDING`·`ACTIVE`·`SUSPENDED` 계정을 `DEACTIVATED`로 바꾸고 ACC-04를 한 트랜잭션에서 처리합니다. 되돌릴 수 없습니다.
     *
     * @throws com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException 없는 principal
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException owner 계정(GOV-03), admin이 admin 계정 비활성화(GOV-02)
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException 이미 `DEACTIVATED`
     */
    fun deactivate(command: DeactivateAccountCommand)
}

/**
 * @property managerId 요청한 관리자 (직원)
 * @property reason 비활성화 사유. 감사 로그에 남깁니다
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class DeactivateAccountCommand(
    val managerId: UUID,
    val principalId: UUID,
    val reason: String,
    val ip: String? = null,
    val userAgent: String? = null,
)
