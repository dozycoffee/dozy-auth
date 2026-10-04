package com.dozycoffee.auth.server.application.port.outbound.client

import java.util.UUID

/**
 * ACC-04 비활성화할 때 system client의 `client_id`를 `SystemClient.deactivatedClientId`로 바꾸고 `client_secret_hash`를 지웁니다.
 * 같은 `client_id`로 다시 등록할 수 있게 하고, 이후 그 client의 토큰 발급은 `invalid_client`가 됩니다.
 * 상태 변경과 다른 정리 작업은 UseCase가 같은 트랜잭션에서 묶습니다.
 */
interface ScrubSystemClientPort {
    /** 바꿨으면 `true`, system client 행이 없으면 `false`입니다. */
    fun scrubSystemClient(principalId: UUID): Boolean
}
