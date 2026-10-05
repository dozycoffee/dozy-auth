package com.dozycoffee.auth.server.application.port.inbound.admin

import java.util.UUID

/** owner 양도 수락 (api/admin.md owner 양도 수락, GOV-09, GOV-10). */
interface AcceptOwnerTransferUseCase {
    /**
     * 한 트랜잭션에서 기존 owner의 `auth:owner`를 회수해 수락한 직원에게 부여하고, 기존 owner의 모든 세션을 폐기합니다.
     * 이전 owner에게 보내는 완료 메일은 커밋 후 보냅니다 (AUD-03).
     *
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException 로그인한 직원이 양도 대상이 아님
     * @throws com.dozycoffee.auth.server.domain.verification.VerificationExpiredException 만료, 사용, 취소된 양도.
     *   요청한 owner가 지금 owner가 아니거나 대상이 `ACTIVE`가 아니게 된 양도도 같습니다
     */
    fun acceptOwnerTransfer(command: AcceptOwnerTransferCommand)
}

/**
 * @property principalId 수락하는 직원 (로그인한 본인, 토큰의 주체)
 * @property token 수락 링크의 토큰 원문. [toString]에서 가립니다 (SEC-03)
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class AcceptOwnerTransferCommand(
    val principalId: UUID,
    val token: String,
    val ip: String? = null,
    val userAgent: String? = null,
) {
    override fun toString(): String = "AcceptOwnerTransferCommand(principalId=$principalId, token=***, ip=$ip, userAgent=$userAgent)"
}
