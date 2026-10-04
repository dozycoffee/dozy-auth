package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.EmployeeDetail
import com.dozycoffee.auth.server.application.port.inbound.admin.GetEmployeeUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** 직원 상세 (api/admin.md 직원 상세). 조회는 보호 규칙(GOV-02) 대상이 아닙니다. */
@Service
class GetEmployeeService(
    private val employees: EmployeeAdministration,
    private val clock: Clock,
) : GetEmployeeUseCase {
    @Transactional(readOnly = true)
    override fun getEmployee(principalId: UUID): EmployeeDetail = employees.detail(principalId, clock.instant())
}
