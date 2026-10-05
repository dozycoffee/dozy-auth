package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.application.port.inbound.admin.RotateClientSecretCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.RotateClientSecretUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.client.RotateClientSecretPort
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.ManagedTarget
import com.dozycoffee.auth.server.domain.authorization.ManagementAction
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.TargetStatus
import com.dozycoffee.auth.server.domain.client.SystemClientNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 system client secret 재발급 (api/admin.md secret 재발급, CLI-02·03, AUD-08).
 *
 * - 검사 순서: 없는 system client(`NOT_FOUND`) → 관리 등급(`FORBIDDEN`) → `ACTIVE`가 아님(`INVALID_STATE`) (GOV-15).
 *   system client가 아닌 principal은 없는 system client로 봅니다. system client는 system role을 가질 수 없어(GOV-06) GOV-02에 걸리지 않습니다.
 * - 대상 계정을 잠가 같은 client의 비활성화(ACC-04)·정지와 차례로 처리합니다. 비활성화가 먼저 끝나면 `INVALID_STATE`입니다.
 * - 해시를 바꾸는 즉시 기존 secret은 무효입니다 (CLI-03). 이미 발급된 system token은 만료까지 유효합니다.
 * - 감사 로그는 `CLIENT_SECRET_ROTATED`입니다. owner에게 즉시 알립니다 (AUD-01, [OwnerAlerts]).
 */
@Service
class RotateClientSecretService(
    private val lockAccount: LockAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val rotateClientSecret: RotateClientSecretPort,
    private val principals: PrincipalAdministration,
    private val ownerAlerts: OwnerAlerts,
    private val clock: Clock,
) : RotateClientSecretUseCase {
    @Transactional
    override fun rotateClientSecret(command: RotateClientSecretCommand): OpaqueSecret {
        val account = lockAccount.lockAccountById(command.principalId)
        if (account == null || account.type != PrincipalType.SYSTEM) throw SystemClientNotFoundException()
        val manager = Manager(command.managerId, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.managerId)))
        val grade = AdminGrade.of(loadPrincipalRoles.findRoleCodes(account.id))
        val target = ManagedTarget(account.id, account.type, grade, TargetStatus.valueOf(account.status.name))
        ManagementPolicy.checkCanManage(manager, target, ManagementAction.ROTATE_CLIENT_SECRET)
        account.ensureClientSecretRotatable()

        // 잠금을 기다린 동안 시간이 지났을 수 있으므로 잠금을 얻은 뒤 읽습니다
        val now = clock.instant()
        val secret = OpaqueSecret.generate()
        if (!rotateClientSecret.rotateSecret(account.id, secret.hash(), now)) throw SystemClientNotFoundException()
        val event = principals.record(AuditAction.CLIENT_SECRET_ROTATED, manager.id, account.id, now, command.ip, command.userAgent)
        ownerAlerts.notifyIfRequired(event)
        return secret
    }
}
