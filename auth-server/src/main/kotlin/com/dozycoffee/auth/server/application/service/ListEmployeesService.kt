package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.EmployeeSummary
import com.dozycoffee.auth.server.application.port.inbound.ListEmployeesCommand
import com.dozycoffee.auth.server.application.port.inbound.ListEmployeesUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.EmployeeSearchCriteria
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeeRecordsPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRoleHoldersPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.domain.Page
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 직원 목록 (api/admin.md 직원 목록).
 *
 * 다른 도메인 테이블과 조인하지 않으므로(architecture.md §8) role 조건은 그 role을 가진 principal을 먼저 구해 계정 조회에 넘기고,
 * 항목의 role은 한 페이지의 principal을 모아 한 번에 조회합니다.
 */
@Service
class ListEmployeesService(
    private val loadEmployeeRecords: LoadEmployeeRecordsPort,
    private val loadRole: LoadRolePort,
    private val loadRoleHolders: LoadRoleHoldersPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
) : ListEmployeesUseCase {
    @Transactional(readOnly = true)
    override fun listEmployees(command: ListEmployeesCommand): Page<EmployeeSummary> {
        val holders =
            command.role?.let { code ->
                // 없는 role을 가진 직원은 없으므로 빈 목록입니다
                val role = loadRole.findRoleByCode(code) ?: return Page.empty(command.page)
                loadRoleHolders.findHolderIds(role.id)
            }
        val criteria = EmployeeSearchCriteria(status = command.status, principalIds = holders, query = command.query)
        val page = loadEmployeeRecords.searchEmployeeRecords(criteria, command.page)
        val roles = loadPrincipalRoles.findRoleCodes(page.items.map { it.id })
        return page.map { record ->
            val (account, profile) = record.employee
            EmployeeSummary(
                principalId = account.id,
                email = profile.email.value,
                name = profile.name,
                status = account.status,
                roles = roles[account.id].orEmpty().map(RoleCode::value),
                createdAt = record.createdAt,
            )
        }
    }
}
