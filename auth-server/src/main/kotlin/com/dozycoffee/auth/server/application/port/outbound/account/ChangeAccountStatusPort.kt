package com.dozycoffee.auth.server.application.port.outbound.account

import com.dozycoffee.auth.server.domain.account.AccountStatus
import java.time.Instant
import java.util.UUID

/**
 * 계정 상태를 바꿉니다. 전이가 허용되는지(ACC-01)는 호출하는 쪽이 `Account`로 먼저 판단합니다.
 *
 * 조회한 뒤 다른 요청이 상태를 바꿨을 수 있으므로, 현재 상태가 [from]일 때만 바꾸는 것을 한 문장으로 처리합니다.
 */
interface ChangeAccountStatusPort {
    /**
     * [to]가 `DEACTIVATED`이면 `deactivated_at`도 [changedAt]으로 남깁니다.
     *
     * @return 바꿨으면 `true`. 계정이 없거나 현재 상태가 [from]이 아니면 아무것도 바꾸지 않고 `false`이며,
     *   호출하는 쪽은 `INVALID_STATE`로 응답합니다
     */
    fun changeStatus(
        id: UUID,
        from: AccountStatus,
        to: AccountStatus,
        changedAt: Instant,
    ): Boolean
}
