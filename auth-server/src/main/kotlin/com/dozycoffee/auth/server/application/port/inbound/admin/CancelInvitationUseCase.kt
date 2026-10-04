package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/** 관리자의 초대 취소 (api/admin.md 초대 취소, ACC-06). `PENDING` 직원을 비활성화하며 ACC-04를 따릅니다. */
interface CancelInvitationUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.account.EmployeeNotFoundException 없는 직원
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException owner 계정(GOV-03), admin이 admin 계정의 초대 취소(GOV-02)
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException `PENDING`이 아님
     */
    fun cancelInvitation(command: CancelInvitationCommand)
}

/**
 * @property managerId 요청한 관리자 (직원)
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class CancelInvitationCommand(
    val managerId: UUID,
    val principalId: UUID,
    val ip: String? = null,
    val userAgent: String? = null,
)
