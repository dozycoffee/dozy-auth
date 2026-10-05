package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.GrantRolesCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.GrantRolesUseCase
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LockRolePort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * role 부여 (api/admin.md role 부여, GOV-04~08, GOV-15).
 *
 * - 검사 순서: 없는 principal, 없는 role(`NOT_FOUND`) → GOV-05 → GOV-04 → GOV-02 → GOV-06 → GOV-07.
 * - 한 트랜잭션에서 role마다 부여하므로 하나라도 실패하면 전체가 적용되지 않습니다 (GOV-08). 권한 검사를 모두 통과한 뒤에 부여합니다.
 * - 이미 가진 role은 무시합니다 (GOV-08). 감사 로그 `ROLE_GRANTED`(`detail.roles`)에는 새로 부여한 role만 담고, 새로 부여한 role이
 *   없으면 남기지 않습니다 (AUD-08).
 * - role 정의를 부여용으로 잠가, 동시에 삭제된 role의 부여가 외래 키 위반(500)이 되지 않고 `NOT_FOUND`가 되게 합니다 (LockRolePort).
 * - 세션은 건드리지 않습니다. 대상의 다음 토큰 갱신부터 반영됩니다 (SES-05). `auth` audience role을 부여하면(admin 임명) owner에게 즉시 알립니다 (AUD-02, [OwnerAlerts]).
 */
@Service
class GrantRolesService(
    private val roleAssignment: RoleAssignment,
    private val ownerAlerts: OwnerAlerts,
    private val lockRole: LockRolePort,
    private val grantRole: GrantRolePort,
    private val clock: Clock,
) : GrantRolesUseCase {
    @Transactional
    override fun grantRoles(command: GrantRolesCommand) {
        val (manager, target) = roleAssignment.lockTarget(command.manager, command.principalId)
        val roles = lockRole.lockRolesForGrant(command.roles)
        if (roles.size != command.roles.size) throw RoleNotFoundException()
        ManagementPolicy.checkCanGrant(manager, target, command.roles)

        val now = clock.instant()
        val granted =
            roles
                .filter { grantRole.grant(RoleGrant(target.id, it.id, manager.id, now)) }
                .map { it.code }
                .sortedBy { it.value }
        if (granted.isEmpty()) return
        val event = roleAssignment.record(AuditAction.ROLE_GRANTED, command.manager, target.id, granted, now, command.ip, command.userAgent)
        ownerAlerts.notifyIfRequired(event)
    }
}
