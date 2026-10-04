package com.dozycoffee.auth.server.application.port.inbound.admin

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.client.ClientId
import java.util.UUID

/** 관리자의 system client 등록 (api/admin.md system client 등록, CLI-01·02·04, GOV-05·06). */
interface RegisterSystemClientUseCase {
    /**
     * system client를 `ACTIVE`로 만들고 지정한 role을 부여합니다. secret은 결과로 한 번만 돌려주고 해시만 저장합니다 (CLI-02).
     * 하나라도 실패하면 아무것도 남기지 않습니다 (GOV-08).
     *
     * @throws com.dozycoffee.auth.server.domain.authorization.RoleNotFoundException 정의되지 않은 role
     * @throws com.dozycoffee.auth.server.domain.ForbiddenException 관리 등급 없음, system role 지정 (GOV-05, GOV-06)
     * @throws com.dozycoffee.auth.server.domain.client.ClientIdDuplicatedException 같은 `client_id`
     */
    fun registerSystemClient(command: RegisterSystemClientCommand): RegisteredSystemClient
}

/**
 * system client 등록 요청.
 *
 * @property managerId 요청한 관리자 (직원). 관리 등급은 토큰이 아니라 DB의 현재 role로 정합니다 (GOV-14)
 * @property roles 함께 부여할 일반 role. 비어 있으면 role 없이 등록합니다
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 */
data class RegisterSystemClientCommand(
    val managerId: UUID,
    val clientId: ClientId,
    val name: String,
    val roles: Set<RoleCode>,
    val ip: String? = null,
    val userAgent: String? = null,
)

/**
 * 등록한 system client. [secret]은 이 결과로만 알 수 있으며 `toString`에서 가려집니다 (SEC-03).
 */
data class RegisteredSystemClient(
    val principalId: UUID,
    val clientId: ClientId,
    val secret: OpaqueSecret,
)
