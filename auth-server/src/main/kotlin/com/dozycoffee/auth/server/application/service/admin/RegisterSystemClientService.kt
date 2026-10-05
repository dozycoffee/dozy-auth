package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.server.application.port.inbound.admin.RegisterSystemClientCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.RegisterSystemClientUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.RegisteredSystemClient
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LockRolePort
import com.dozycoffee.auth.server.application.port.outbound.client.CreateSystemClientPort
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.authorization.AdminGrade
import com.dozycoffee.auth.server.domain.authorization.ManagementPolicy
import com.dozycoffee.auth.server.domain.authorization.Manager
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자의 system client 등록 (api/admin.md system client 등록, CLI-01·02·04, GOV-05·06·08, AUD-08).
 *
 * - 검사 순서: 없는 role(`NOT_FOUND`) → 관리 등급·GOV-05·GOV-06(`FORBIDDEN`) → `client_id` 중복(`CLIENT_ID_DUPLICATED`).
 *   직원 초대(GOV-15)와 같은 순서이며, 권한 검사를 모두 통과한 뒤에 계정을 만듭니다. 관리 등급은 DB의 현재 role로 정합니다 (GOV-14).
 * - principal(`system`, `ACTIVE`), `system_client`, role 부여, 감사 기록을 한 트랜잭션에서 하므로 하나라도 실패하면 아무것도 남지 않습니다
 *   (GOV-08). role 정의를 부여용으로 잠가 동시에 삭제된 role은 `NOT_FOUND`가 됩니다 (LockRolePort).
 * - secret은 `policy.secret-bytes` 난수이며 해시만 저장하고 원문은 결과로 한 번만 돌려줍니다 (CLI-02, SEC-01). 로그에 쓰지 않습니다 (SEC-03).
 * - 감사 로그는 `SYSTEM_CLIENT_REGISTERED`(`detail.clientId`)와, role을 지정했으면 `ROLE_GRANTED`(`detail.roles`)입니다 (AUD-08).
 *   owner에게 즉시 알립니다. 알림은 `ROLE_GRANTED`가 있어도 한 통입니다 (AUD-01, [OwnerAlerts]).
 */
@Service
class RegisterSystemClientService(
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val lockRole: LockRolePort,
    private val createSystemClient: CreateSystemClientPort,
    private val grantRole: GrantRolePort,
    private val principals: PrincipalAdministration,
    private val ownerAlerts: OwnerAlerts,
    private val clock: Clock,
) : RegisterSystemClientUseCase {
    @Transactional
    override fun registerSystemClient(command: RegisterSystemClientCommand): RegisteredSystemClient {
        val roles = if (command.roles.isEmpty()) emptyList() else lockRole.lockRolesForGrant(command.roles)
        if (roles.size != command.roles.size) throw RoleNotFoundException()
        val manager = Manager(command.managerId, AdminGrade.of(loadPrincipalRoles.findRoleCodes(command.managerId)))
        ManagementPolicy.checkCanRegisterSystemClient(manager, command.roles)

        // role 잠금을 기다린 동안 시간이 지났을 수 있으므로 잠금을 얻은 뒤 읽습니다
        val now = clock.instant()
        val secret = OpaqueSecret.generate()
        val client = createSystemClient.createSystemClient(command.clientId, command.name, secret.hash(), now)
        roles.forEach { grantRole.grant(RoleGrant(client.principalId, it.id, manager.id, now)) }

        val clientDetail = mapOf("clientId" to command.clientId.value)
        val registered =
            principals.record(
                AuditAction.SYSTEM_CLIENT_REGISTERED,
                manager.id,
                client.principalId,
                now,
                command.ip,
                command.userAgent,
                clientDetail,
            )
        val granted =
            if (roles.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    principals.record(
                        AuditAction.ROLE_GRANTED,
                        manager.id,
                        client.principalId,
                        now,
                        command.ip,
                        command.userAgent,
                        mapOf("roles" to roles.map { it.code.value }.sorted()),
                    ),
                )
            }
        ownerAlerts.notifyIfRequired(registered, granted)
        return RegisteredSystemClient(client.principalId, command.clientId, secret)
    }
}
