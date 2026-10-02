package com.dozycoffee.auth.server.application.port.outbound.client

import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.SystemClient

/** system client를 조회합니다 (data-model.md §3.4). 계정 상태는 계정 포트로 따로 조회합니다 (architecture.md §8). */
interface LoadSystemClientPort {
    fun findByClientId(clientId: ClientId): SystemClient?
}
