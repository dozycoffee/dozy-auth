package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.RevokeRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.RevokeRoleUseCase
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.RevokeRolePort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * role 회수 (api/admin.md role 회수, GOV-05, GOV-08, GOV-15).
 *
 * - 검사 순서: 없는 principal, 없는 role 정의(`NOT_FOUND`) → GOV-05 → GOV-02. 대상의 상태와 principal type은 보지 않습니다.
 * - 가지지 않은 role을 회수해도 성공이며(GOV-08), 감사 로그 `ROLE_REVOKED`(`detail.roles`)는 실제로 회수했을 때만 남깁니다 (AUD-08).
 * - 세션은 폐기하지 않습니다. 대상의 다음 토큰 갱신부터 반영되고, 이미 발급된 access token은 만료까지 유효합니다 (SES-05, SES-07).
 */
@Service
class RevokeRoleService(
    private val roleAssignment: RoleAssignment,
    private val loadRole: LoadRolePort,
    private val revokeRole: RevokeRolePort,
    private val clock: Clock,
) : RevokeRoleUseCase {
    @Transactional
    override fun revokeRole(command: RevokeRoleCommand) {
        val (manager, target) = roleAssignment.lockTarget(command.manager, command.principalId)
        val role = loadRole.findRoleByCode(command.role) ?: throw RoleNotFoundException()
        ManagementPolicy.checkCanRevoke(manager, target, role.code)

        if (!revokeRole.revoke(target.id, role.id)) return
        roleAssignment.record(
            AuditAction.ROLE_REVOKED,
            command.manager,
            target.id,
            listOf(role.code),
            clock.instant(),
            command.ip,
            command.userAgent,
        )
    }
}
