package com.dozycoffee.auth.server.application.port.outbound.account

import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.DuplicateEmailException
import com.dozycoffee.auth.server.domain.account.Employee
import java.time.Instant

/** 초대(GOV-11 부트스트랩 포함)로 직원 계정을 `PENDING` 상태로 만듭니다 (ACC-01). id는 DB가 UUIDv7으로 만듭니다. */
interface CreateEmployeePort {
    /**
     * principal과 `employee_profile`을 함께 만듭니다. 이메일이 겹치면 아무것도 남기지 않고 예외를 던지며,
     * 호출한 트랜잭션은 계속 쓸 수 있습니다.
     *
     * @throws DuplicateEmailException 대소문자를 무시하고 같은 이메일의 직원이 이미 있을 때
     */
    fun createEmployee(
        email: Email,
        name: String,
        phone: String?,
        address: String?,
        createdAt: Instant,
    ): Employee
}
