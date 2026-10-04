package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.EmployeeDetail
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeeRecordsPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.EmployeeNotFoundException
import com.dozycoffee.auth.server.domain.account.EmployeeRecord
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.ManagedTarget
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.TargetStatus
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * 직원 관리 API(api/admin.md §1)가 함께 쓰는 조회와 권한 검사.
 *
 * 관리 등급(GOV-01)은 토큰이 아니라 DB의 현재 role로 정합니다. 토큰의 role은 만료까지 남아 있으므로(SES-07) 그사이 회수된
 * 권한으로 변경하지 못하게 하기 위해서입니다. 웹 계층의 필요 role 검사(GOV-14)는 토큰으로 먼저 합니다.
 */
@Component
class EmployeeAdministration(
    private val loadEmployeeRecords: LoadEmployeeRecordsPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val loadVerification: LoadVerificationPort,
) {
    /**
     * 변경할 직원을 찾고 [action]을 할 수 있는지 검사합니다 (GOV-15: `NOT_FOUND` → GOV-03 → GOV-02).
     * 계정 상태(ACC-01)는 호출하는 쪽이 이 검사 뒤에 판단합니다.
     *
     * @throws EmployeeNotFoundException 없는 직원
     */
    fun findManageable(
        managerId: UUID,
        principalId: UUID,
        action: ManagementAction,
    ): ManageableEmployee {
        // 같은 직원에 대한 변경이 동시에 오면 차례로 처리하도록 잠그고 읽습니다
        val record = loadEmployeeRecords.lockEmployeeRecord(principalId) ?: throw EmployeeNotFoundException()
        val manager = Manager(managerId, AdminGrade.of(loadPrincipalRoles.findRoleCodes(managerId)))
        val roles = loadPrincipalRoles.findRoleCodes(principalId)
        val account = record.employee.account
        val target = ManagedTarget(account.id, account.type, AdminGrade.of(roles), TargetStatus.valueOf(account.status.name))
        ManagementPolicy.checkCanManage(manager, target, action)
        return ManageableEmployee(record, roles)
    }

    /** @throws EmployeeNotFoundException 없는 직원 */
    fun detail(
        principalId: UUID,
        now: Instant,
    ): EmployeeDetail {
        val record = loadEmployeeRecords.findEmployeeRecord(principalId) ?: throw EmployeeNotFoundException()
        return detail(record, loadPrincipalRoles.findRoleCodes(principalId), now)
    }

    fun detail(
        record: EmployeeRecord,
        roles: List<RoleCode>,
        now: Instant,
    ): EmployeeDetail {
        val (account, profile) = record.employee
        val invitation =
            if (account.status == AccountStatus.PENDING) {
                loadVerification.findUnfinished(account.id, VerificationPurpose.EMPLOYEE_INVITATION)
            } else {
                null
            }
        return EmployeeDetail(
            principalId = account.id,
            email = profile.email.value,
            name = profile.name,
            phone = profile.phone,
            address = profile.address,
            status = account.status,
            roles = roles.map(RoleCode::value),
            lockedUntil = account.lockedUntil?.takeIf { account.isLocked(now) },
            invitationExpiresAt = invitation?.expiresAt,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )
    }

    companion object {
        /** 관리 API를 호출한 직원. 관리 API는 직원 토큰만 받습니다 (api/conventions.md §2). */
        fun actor(managerId: UUID): AuditActor = AuditActor(managerId, PrincipalType.EMPLOYEE)
    }
}

/** 권한 검사를 통과한 변경 대상 직원과 그 직원의 현재 role. */
data class ManageableEmployee(
    val record: EmployeeRecord,
    val roles: List<RoleCode>,
)
