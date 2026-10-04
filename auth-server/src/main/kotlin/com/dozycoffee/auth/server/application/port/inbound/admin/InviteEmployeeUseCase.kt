package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.AccountStatus
import java.time.Instant
import java.util.UUID

/** 관리자의 직원 초대 (api/admin.md 직원 초대, VER-01 `EMPLOYEE_INVITATION`, GOV-05~08). */
interface InviteEmployeeUseCase {
    /**
     * 직원을 `PENDING`으로 만들고 지정한 role을 부여한 뒤 초대를 발급합니다. 하나라도 실패하면 아무것도 남기지 않고(GOV-08),
     * 메일은 커밋 후 보냅니다.
     *
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException 정의되지 않은 role
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException 관리 등급 없음, `auth:owner` 지정, owner가 아닌데 `auth:admin` 지정 (GOV-05)
     * @throws com.dozycoffee.auth.server.domain.account.DuplicateEmailException 이미 사용 중인 직원 이메일
     */
    fun inviteEmployee(command: InviteEmployeeCommand): InvitedEmployee
}

/**
 * 직원 초대 요청.
 *
 * @property managerId 요청한 관리자 (직원). 관리 등급은 토큰이 아니라 DB의 현재 role로 정합니다
 * @property roles 함께 부여할 role. 비어 있으면 role 없이 초대합니다
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class InviteEmployeeCommand(
    val managerId: UUID,
    val email: Email,
    val name: String,
    val phone: String?,
    val address: String?,
    val roles: Set<RoleCode>,
    val ip: String? = null,
    val userAgent: String? = null,
) {
    /** 개인정보 값은 가립니다. */
    override fun toString(): String = "InviteEmployeeCommand(managerId=$managerId, roles=$roles)"
}

/** 초대한 직원. [status]는 언제나 `PENDING`입니다. */
data class InvitedEmployee(
    val principalId: UUID,
    val status: AccountStatus,
    val invitationExpiresAt: Instant,
)
