package com.dozycoffee.auth.server.application.port.outbound.client

import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.SystemClient

/** system client를 조회합니다 (data-model.md §3.4). 계정 상태는 계정 포트로 따로 조회합니다 (architecture.md §8). */
interface LoadSystemClientPort {
    fun findByClientId(clientId: ClientId): SystemClient?

    /** 모든 system client (api/admin.md system client 목록). 비활성화한 client(ACC-04)도 포함하며, 최근 등록한 순서입니다. */
    fun findAll(): List<SystemClient>
}
