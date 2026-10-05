package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/** owner 양도 취소 (api/admin.md owner 양도 취소, GOV-09). */
interface CancelOwnerTransferUseCase {
    /**
     * 진행 중인 `OWNER_TRANSFER`를 무효화합니다. 발송된 수락 링크는 `VERIFICATION_EXPIRED`가 됩니다.
     *
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException DB의 현재 role로 owner가 아님 (GOV-14)
     * @throws com.dozycoffee.auth.server.domain.authorization.OwnerTransferNotFoundException 진행 중인 양도 없음
     */
    fun cancelOwnerTransfer(command: CancelOwnerTransferCommand)
}

/**
 * @property ownerId 요청한 owner (직원)
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class CancelOwnerTransferCommand(
    val ownerId: UUID,
    val ip: String? = null,
    val userAgent: String? = null,
)
