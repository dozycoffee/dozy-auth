package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.Employee
import com.dozycoffee.auth.server.domain.verification.Verification
import com.dozycoffee.auth.server.domain.verification.VerificationExpiredException
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import java.time.Instant

/** 살아 있는 직원 초대와 초대받은 직원. */
internal data class Invitation(
    val verification: Verification,
    val employee: Employee,
)

/**
 * 초대 조회·수락이 함께 쓰는 확인. [token]의 `EMPLOYEE_INVITATION`이 [now]에 살아 있고 초대받은 계정이 `PENDING` 직원이어야 합니다.
 *
 * 계정이 `PENDING`이 아니면 초대도 쓸 수 없는 것으로 보고 `VERIFICATION_EXPIRED`입니다 (VER-04). 정상 흐름에서는 수락하면 초대가
 * 소비되고 비활성화하면 무효화되므로(ACC-04) 생기지 않는 경우이며, 계정 상태를 응답으로 드러내지 않습니다.
 *
 * @throws VerificationExpiredException 위 조건을 하나라도 만족하지 않을 때
 */
internal fun findInvitation(
    token: String,
    now: Instant,
    loadVerification: LoadVerificationPort,
    loadEmployee: LoadEmployeePort,
): Invitation {
    val found = loadVerification.findByTokenHash(SecretHash.of(token))
    val verification = Verification.requireUsable(found, VerificationPurpose.EMPLOYEE_INVITATION, now)
    val employee = loadEmployee.findEmployeeById(verification.principalId)
    if (employee == null || employee.account.status != AccountStatus.PENDING) throw VerificationExpiredException()
    return Invitation(verification, employee)
}
