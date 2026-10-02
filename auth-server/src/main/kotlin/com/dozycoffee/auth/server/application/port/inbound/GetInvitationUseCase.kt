package com.dozycoffee.auth.server.application.port.inbound

import java.time.Instant

/** 초대 링크로 초대 정보를 확인합니다 (api/account.md 초대 조회). 토큰을 소비하지 않습니다 (VER-06). */
interface GetInvitationUseCase {
    /**
     * @param token 초대 링크의 토큰 원문. 로그에 남기지 않습니다 (SEC-03)
     * @throws com.dozycoffee.auth.server.domain.verification.VerificationExpiredException 없거나 살아 있지 않은 초대,
     *   초대받은 계정이 `PENDING` 직원이 아닐 때 (VER-04)
     */
    fun getInvitation(token: String): InvitationSummary
}

/**
 * 초대 조회 결과. 이메일과 전화번호는 마스킹한 값입니다 (SEC-02).
 *
 * @property maskedPhone 전화번호가 없으면 `null`
 * @property expiresAt 초대 만료 시각
 */
data class InvitationSummary(
    val name: String,
    val maskedEmail: String,
    val maskedPhone: String?,
    val expiresAt: Instant,
)
