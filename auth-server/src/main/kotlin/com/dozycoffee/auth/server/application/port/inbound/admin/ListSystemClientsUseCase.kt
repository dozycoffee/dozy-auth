package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.server.domain.account.AccountStatus
import java.time.Instant
import java.util.UUID

/** system client 목록 (api/admin.md system client 목록). */
interface ListSystemClientsUseCase {
    /** 모든 system client. 비활성화한 client도 포함하며 최근 등록한 순서입니다. secret은 담지 않습니다 (CLI-02). */
    fun listSystemClients(): List<SystemClientSummary>
}

/**
 * system client 목록의 항목.
 *
 * @property clientId 비활성화한 client는 `deleted-{principalId}` (ACC-04)
 * @property roles `{audience}:{code}` 순서
 */
data class SystemClientSummary(
    val principalId: UUID,
    val clientId: String,
    val name: String,
    val status: AccountStatus,
    val roles: List<String>,
    val secretRotatedAt: Instant,
    val createdAt: Instant,
)
