package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.EmployeeDetail
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateEmployeeCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateEmployeeUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.applyTo
import com.dozycoffee.auth.server.application.port.outbound.account.UpdateEmployeeProfilePort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.domain.account.EmployeeNotFoundException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 직원 정보 수정 (api/admin.md 직원 정보 수정, ACC-07).
 *
 * - 검사 순서는 GOV-15를 따릅니다: 없는 직원(`NOT_FOUND`) → GOV-02 → 계정 상태(`DEACTIVATED`면 `INVALID_STATE`).
 * - 보낸 값이 지금 값과 같으면 바뀐 것이 아니므로, 바뀐 필드가 없으면 저장하지 않고 감사 로그도 남기지 않습니다.
 * - 감사 로그 `PROFILE_UPDATED`에는 바뀐 필드 이름만 `detail.fields`로 남깁니다 (AUD-07).
 */
@Service
class UpdateEmployeeService(
    private val employees: EmployeeAdministration,
    private val updateEmployeeProfile: UpdateEmployeeProfilePort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : UpdateEmployeeUseCase {
    @Transactional
    override fun updateEmployee(command: UpdateEmployeeCommand): EmployeeDetail {
        val now = clock.instant()
        val target = employees.findManageable(command.managerId, command.principalId, ManagementAction.UPDATE_PROFILE)
        val (account, profile) = target.record.employee
        account.ensureProfileEditable()

        val updated =
            profile.copy(
                name = command.name.applyTo(profile.name),
                phone = command.phone.applyTo(profile.phone),
                address = command.address.applyTo(profile.address),
            )
        val changedFields =
            buildList {
                if (updated.name != profile.name) add(FIELD_NAME)
                if (updated.phone != profile.phone) add(FIELD_PHONE)
                if (updated.address != profile.address) add(FIELD_ADDRESS)
            }
        if (changedFields.isEmpty()) return employees.detail(target.record, target.roles, now)

        if (!updateEmployeeProfile.updateEmployeeProfile(account.id, updated.name, updated.phone, updated.address, now)) {
            throw EmployeeNotFoundException()
        }
        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.PROFILE_UPDATED,
                actor = EmployeeAdministration.actor(command.managerId),
                target = AuditTarget.principal(account.id),
                detail = mapOf("fields" to changedFields),
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
        return employees.detail(account.id, now)
    }

    private companion object {
        // 감사 로그 detail.fields의 값. 요청 필드 이름과 같습니다 (api/admin.md 직원 정보 수정)
        const val FIELD_NAME = "name"
        const val FIELD_PHONE = "phone"
        const val FIELD_ADDRESS = "address"
    }
}
