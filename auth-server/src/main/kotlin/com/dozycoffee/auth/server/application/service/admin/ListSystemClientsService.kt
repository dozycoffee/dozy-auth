package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.admin.ListSystemClientsUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.SystemClientSummary
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.client.LoadSystemClientPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * system client 목록 (api/admin.md system client 목록).
 *
 * 다른 도메인 테이블과 조인하지 않으므로(architecture.md §8) 계정 상태와 role은 client의 principal을 모아 한 번에 조회합니다.
 * secret은 해시도 담지 않습니다 (CLI-02).
 */
@Service
class ListSystemClientsService(
    private val loadSystemClient: LoadSystemClientPort,
    private val loadAccount: LoadAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
) : ListSystemClientsUseCase {
    @Transactional(readOnly = true)
    override fun listSystemClients(): List<SystemClientSummary> {
        val clients = loadSystemClient.findAll()
        val ids = clients.map { it.principalId }
        val accounts = loadAccount.findAccountsByIds(ids)
        val roles = loadPrincipalRoles.findRoleCodes(ids)
        return clients.map { client ->
            SystemClientSummary(
                principalId = client.principalId,
                clientId = client.clientId,
                name = client.name,
                // system_client는 principal을 외래 키로 참조하므로 계정은 언제나 있습니다
                status = checkNotNull(accounts[client.principalId]).status,
                roles = roles[client.principalId].orEmpty().map(RoleCode::value),
                secretRotatedAt = client.secretRotatedAt,
                createdAt = client.createdAt,
            )
        }
    }
}
