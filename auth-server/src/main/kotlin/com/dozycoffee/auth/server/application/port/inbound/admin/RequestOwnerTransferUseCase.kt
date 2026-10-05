package com.dozycoffee.auth.server.application.port.inbound.admin

import java.time.Instant
import java.util.UUID

/** owner 양도 요청 (api/admin.md owner 양도 요청, GOV-09, VER-01 `OWNER_TRANSFER`). */
interface RequestOwnerTransferUseCase {
    /**
     * 대상 직원에게 `OWNER_TRANSFER`를 발급하고, 수락 메일은 커밋 후 보냅니다. owner role은 수락할 때 옮깁니다.
     *
     * @return 수락 링크의 만료 시각
     * @throws com.dozycoffee.auth.server.domain.account.PrincipalNotFoundException 대상 없음
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException DB의 현재 role로 owner가 아님 (GOV-14)
     * @throws com.dozycoffee.auth.server.domain.authorization.OwnerTransferNotAllowedException 대상이 `ACTIVE` 직원이 아님,
     *   자기 자신, 진행 중인 양도가 있음
     */
    fun requestOwnerTransfer(command: RequestOwnerTransferCommand): Instant
}

/**
 * @property ownerId 요청한 owner (직원)
 * @property targetId 양도 대상 직원
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class RequestOwnerTransferCommand(
    val ownerId: UUID,
    val targetId: UUID,
    val ip: String? = null,
    val userAgent: String? = null,
)
