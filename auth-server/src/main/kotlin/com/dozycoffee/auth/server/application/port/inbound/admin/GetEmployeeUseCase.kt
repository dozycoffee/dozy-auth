package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.server.domain.account.AccountStatus
import java.time.Instant
import java.util.UUID

/** 관리 화면의 직원 상세 (api/admin.md 직원 상세). admin도 owner·admin 계정을 조회할 수 있습니다 (GOV-02). */
interface GetEmployeeUseCase {
    /** @throws com.dozycoffee.auth.server.domain.account.EmployeeNotFoundException 없는 id, 직원이 아닌 principal */
    fun getEmployee(principalId: UUID): EmployeeDetail
}

/**
 * 직원 상세. 정보 수정 응답도 같은 형식입니다.
 *
 * @property roles `{audience}:{code}` 목록
 * @property lockedUntil 로그인 실패로 잠긴 동안만 값이 있고(ACC-02), 잠기지 않았으면 `null`
 * @property invitationExpiresAt `PENDING`일 때 아직 소비·무효화되지 않은 초대의 만료 시각. 이미 지났으면 만료된 초대입니다.
 *   `PENDING`이 아니면 `null`
 */
data class EmployeeDetail(
    val principalId: UUID,
    val email: String,
    val name: String,
    val phone: String?,
    val address: String?,
    val status: AccountStatus,
    val roles: List<String>,
    val lockedUntil: Instant?,
    val invitationExpiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
