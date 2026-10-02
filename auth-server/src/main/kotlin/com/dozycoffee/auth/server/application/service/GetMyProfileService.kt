package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.GetMyProfileUseCase
import com.dozycoffee.auth.server.application.port.inbound.MyProfile
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.domain.UnauthenticatedException
import com.dozycoffee.auth.server.domain.account.AccountStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 내 정보 (api/auth.md). `roles`는 토큰이 아니라 DB의 현재 값입니다.
 *
 * 비활성화된 계정은 개인정보가 파기됐으므로(ACC-04) 없는 계정과 같게 `401 UNAUTHENTICATED`입니다.
 * 정지 등 다른 상태는 이미 발급된 토큰이 만료까지 유효하므로(SES-07) 그대로 보여 줍니다.
 */
@Service
class GetMyProfileService(
    private val loadEmployee: LoadEmployeePort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
) : GetMyProfileUseCase {
    @Transactional(readOnly = true)
    override fun getMyProfile(principal: PrincipalKey): MyProfile {
        require(principal.type == PrincipalType.EMPLOYEE) { "${principal.type.claimValue} 내 정보는 아직 제공하지 않습니다" }
        val employee = loadEmployee.findEmployeeById(principal.id)
        if (employee == null || employee.account.status == AccountStatus.DEACTIVATED) throw UnauthenticatedException()

        return MyProfile(
            principal = principal,
            name = employee.profile.name,
            email = employee.profile.email.value,
            roles = loadPrincipalRoles.findRoleCodes(principal.id).map(RoleCode::value),
        )
    }
}
