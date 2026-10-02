package com.dozycoffee.auth.server.domain.account

/**
 * 계정 상태 (ACC-01). 이름은 DB의 `principal.status` 값과 같습니다 (data-model.md §3.1).
 *
 * 로그인 실패 잠금은 상태가 아니라 `locked_until`로 표현합니다 (ACC-02).
 */
enum class AccountStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    DEACTIVATED,
    ;

    /** ACC-01 상태 전이 그림에 있는 전이인지. 같은 상태로의 전이와 `DEACTIVATED`에서 나가는 전이(ACC-04)는 없습니다. */
    fun canTransitionTo(target: AccountStatus): Boolean = target in TRANSITIONS.getValue(this)

    private companion object {
        val TRANSITIONS: Map<AccountStatus, Set<AccountStatus>> =
            mapOf(
                PENDING to setOf(ACTIVE, DEACTIVATED),
                ACTIVE to setOf(SUSPENDED, DEACTIVATED),
                SUSPENDED to setOf(ACTIVE, DEACTIVATED),
                DEACTIVATED to emptySet(),
            )
    }
}
