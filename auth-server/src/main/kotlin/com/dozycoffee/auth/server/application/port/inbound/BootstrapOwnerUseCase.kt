package com.dozycoffee.auth.server.application.port.inbound

import com.dozycoffee.auth.server.domain.Email

/** GOV-11 기동할 때 owner가 없으면 설정 이메일로 초대하고, 수락 전 owner의 초대가 만료됐으면 다시 발급합니다 (ADR-0010). */
interface BootstrapOwnerUseCase {
    /**
     * 인스턴스 여러 대가 동시에 불러도 한 번에 하나씩 실행되며, 뒤에 실행된 쪽은 앞에서 만든 owner를 봅니다.
     *
     * @throws com.dozycoffee.auth.server.domain.authorization.OwnerAlreadyAssignedException 잠금 밖에서(예: 수동 SQL) 그 사이에
     *   owner가 생겼을 때 (GOV-10). 아무것도 바꾸지 않습니다
     */
    fun bootstrap(command: BootstrapOwnerCommand): BootstrapOwnerResult
}

/**
 * 부트스트랩 요청.
 *
 * @property ownerEmail `BOOTSTRAP_OWNER_EMAIL`. 설정하지 않았으면 `null`
 */
data class BootstrapOwnerCommand(
    val ownerEmail: Email?,
)

/**
 * 부트스트랩 결과.
 *
 * @property configuredEmailIgnored owner가 이미 있는데 설정 이메일이 owner의 이메일과 달라 무시했는지.
 *   설정값으로 owner를 바꾸지 않습니다 (GOV-11)
 */
data class BootstrapOwnerResult(
    val outcome: BootstrapOwnerOutcome,
    val configuredEmailIgnored: Boolean = false,
)

/** 부트스트랩이 한 일. */
enum class BootstrapOwnerOutcome {
    /** owner가 없어 설정 이메일로 직원을 만들고 `auth:owner`를 부여하고 초대했습니다. */
    OWNER_INVITED,

    /** owner가 `PENDING`이고 살아 있는 초대가 없어 다시 발급했습니다. */
    INVITATION_REISSUED,

    /** owner가 `PENDING`이고 초대가 아직 살아 있어 아무것도 하지 않았습니다. */
    INVITATION_LIVE,

    /** owner가 초대를 수락했으므로 아무것도 하지 않았습니다. */
    OWNER_ACTIVE,

    /** owner가 없는데 설정 이메일이 없어 아무것도 하지 않았습니다. */
    OWNER_EMAIL_MISSING,

    /** owner가 없는데 설정 이메일을 다른 직원이 쓰고 있어 아무것도 하지 않았습니다. 기존 직원을 owner로 만들지 않습니다. */
    OWNER_EMAIL_IN_USE,
}
