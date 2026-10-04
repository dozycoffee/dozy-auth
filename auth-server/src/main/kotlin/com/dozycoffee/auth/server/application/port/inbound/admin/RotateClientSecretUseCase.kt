package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.server.domain.OpaqueSecret
import java.util.UUID

/** 관리자의 system client secret 재발급 (api/admin.md secret 재발급, CLI-02·03). */
interface RotateClientSecretUseCase {
    /**
     * 새 secret을 만들어 해시를 저장하고 원문을 한 번만 돌려줍니다. 기존 secret은 즉시 무효입니다 (CLI-03).
     * 이미 발급된 system token은 만료까지 유효합니다.
     *
     * @throws com.dozycoffee.auth.server.domain.client.SystemClientNotFoundException 없는 principal, system client가 아님
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException 관리 등급 없음
     * @throws com.dozycoffee.auth.server.domain.account.InvalidAccountStateException `ACTIVE`가 아님
     */
    fun rotateClientSecret(command: RotateClientSecretCommand): OpaqueSecret
}

/**
 * @property managerId 요청한 관리자 (직원)
 * @property principalId 대상 system client
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class RotateClientSecretCommand(
    val managerId: UUID,
    val principalId: UUID,
    val ip: String? = null,
    val userAgent: String? = null,
)
