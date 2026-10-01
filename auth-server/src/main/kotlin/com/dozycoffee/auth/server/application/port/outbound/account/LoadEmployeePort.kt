package com.dozycoffee.auth.server.application.port.outbound.account

import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.Employee
import java.util.UUID

/** 직원 계정(principal + `employee_profile`)을 조회합니다. */
interface LoadEmployeePort {
    /** 직원이 아니거나 없으면 `null`입니다. */
    fun findEmployeeById(id: UUID): Employee?

    /**
     * 대소문자를 구분하지 않고 이메일로 찾습니다 ([Email.lookupKey], `lower(email)` 인덱스). LGN-01 2단계에서 씁니다.
     * 비활성화된 직원은 이메일이 파기됐으므로(ACC-04) 원래 이메일로는 찾을 수 없습니다.
     */
    fun findEmployeeByEmail(email: Email): Employee?
}
