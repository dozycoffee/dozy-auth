package com.dozycoffee.auth.server.application.port.inbound

import java.time.Instant
import java.util.UUID

/** 관리자의 초대 재발송 (api/admin.md 초대 재발송, VER-03). */
interface ResendInvitationUseCase {
    /**
     * 이전 초대를 무효화하고 새 `EMPLOYEE_INVITATION`을 발급하며, 메일은 커밋 후 보냅니다. 감사 로그는 남기지 않습니다 (AUD-08).
     *
     * @return 새 초대의 만료 시각
     * @throws com.dozycoffee.auth.server.domain.account.EmployeeNotFoundException 없는 직원
     * @throws com.dozycoffee.auth.server.domain.authorization.ProtectedAccountException admin이 owner·admin 계정의 초대 재발송 (GOV-02)
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException `PENDING`이 아님
     */
    fun resendInvitation(command: ResendInvitationCommand): Instant
}

/** @property managerId 요청한 관리자 (직원) */
data class ResendInvitationCommand(
    val managerId: UUID,
    val principalId: UUID,
)
