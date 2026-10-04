package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.ListRolesUseCase
import com.dozycoffee.auth.server.application.port.inbound.RoleDefinition
import com.dozycoffee.auth.server.application.port.outbound.authorization.CountRoleHoldersPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** role 정의 목록과 role마다 가진 principal 수 (api/admin.md role 목록). */
@Service
class ListRolesService(
    private val loadRole: LoadRolePort,
    private val countRoleHolders: CountRoleHoldersPort,
) : ListRolesUseCase {
    @Transactional(readOnly = true)
    override fun listRoles(audienceCode: String?): List<RoleDefinition> {
        val roles = loadRole.findRoles(audienceCode)
        val counts = countRoleHolders.countHolders(roles.map { it.id })
        return roles.map { RoleDefinition(it, counts.getValue(it.id)) }
    }
}
