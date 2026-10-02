package com.dozycoffee.auth.server.domain.account

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 인증 주체(principal) 하나 (docs/domain.md §1, data-model.md §3.1). 타입별 정보는 profile이 가집니다.
 *
 * 값을 바꾸는 함수는 바뀐 사본을 돌려줍니다. 현재 시각은 호출하는 쪽이 `Clock`에서 읽어 넘깁니다.
 *
 * @property id DB가 만든 UUIDv7 (ADR-0028)
 * @property type 생성 후 바뀌지 않음
 * @property failedLoginCount 연속 로그인 실패 횟수 (LGN-01)
 * @property lockedUntil 이 시각 전까지 로그인을 막음 (ACC-02). 이 시각부터는 풀린 것으로 봅니다
 * @property deactivatedAt `DEACTIVATED`로 바뀐 시각
 */
data class Account(
    val id: UUID,
    val type: PrincipalType,
    val status: AccountStatus,
    val failedLoginCount: Int,
    val lockedUntil: Instant?,
    val deactivatedAt: Instant?,
) {
    init {
        require(failedLoginCount >= 0) { "로그인 실패 횟수는 0 이상이어야 합니다" }
    }

    /** ACC-01 초대 수락, 이메일 인증: `PENDING` → `ACTIVE`. */
    fun activate(): Account = transition(from = AccountStatus.PENDING, to = AccountStatus.ACTIVE)

    /** ACC-01 정지 해제: `SUSPENDED` → `ACTIVE`. */
    fun reactivate(): Account = transition(from = AccountStatus.SUSPENDED, to = AccountStatus.ACTIVE)

    /** ACC-01 관리자 정지: `ACTIVE` → `SUSPENDED`. 세션 폐기(ACC-03)는 UseCase가 함께 처리합니다. */
    fun suspend(): Account = transition(from = AccountStatus.ACTIVE, to = AccountStatus.SUSPENDED)

    /**
     * ACC-01 초대 취소, 비활성화, 퇴사, 탈퇴: `PENDING`·`ACTIVE`·`SUSPENDED` → `DEACTIVATED`.
     * 되돌릴 수 없고(ACC-04), 함께 처리할 일(세션·credential·role·verification, profile 파기)은 UseCase가 묶습니다.
     */
    fun deactivate(now: Instant): Account = transition(from = status, to = AccountStatus.DEACTIVATED).copy(deactivatedAt = now)

    /** ACC-02 [now]에 로그인이 잠겨 있는지. */
    fun isLocked(now: Instant): Boolean = lockedUntil != null && now.isBefore(lockedUntil)

    /**
     * LGN-01 1단계: 잠겨 있으면 남은 시간을 `Retry-After`로 담아 거부합니다.
     *
     * @throws TooManyAttemptsException 잠겨 있을 때
     */
    fun ensureNotLocked(now: Instant) {
        if (isLocked(now)) throw TooManyAttemptsException(Duration.between(now, lockedUntil))
    }

    /**
     * LGN-01 3단계: 비밀번호가 틀린 것을 기록합니다. 실패 횟수가 `policy.login-lock-threshold`에 도달하면
     * `policy.login-lock-duration` 동안 잠그고 실패 횟수를 0으로 되돌립니다. 잠금이 풀린 뒤에는 다시 그 횟수만큼 시도할 수 있습니다.
     */
    fun recordLoginFailure(now: Instant): LoginFailureResult {
        val failures = failedLoginCount + 1
        if (failures < AuthPolicy.LOGIN_LOCK_THRESHOLD) {
            return LoginFailureResult(copy(failedLoginCount = failures), locked = false)
        }
        val locked = copy(failedLoginCount = 0, lockedUntil = now.plus(AuthPolicy.LOGIN_LOCK_DURATION))
        return LoginFailureResult(locked, locked = true)
    }

    /** LGN-01 5단계(로그인 성공), PWD-07(비밀번호 재설정): 실패 횟수와 잠금을 초기화합니다. */
    fun resetLoginFailures(): Account = copy(failedLoginCount = 0, lockedUntil = null)

    private fun transition(
        from: AccountStatus,
        to: AccountStatus,
    ): Account {
        if (status != from || !status.canTransitionTo(to)) throw InvalidAccountStateException()
        return copy(status = to)
    }
}

/**
 * 로그인 실패를 기록한 결과.
 *
 * @property account 기록한 뒤의 계정
 * @property locked 이번 실패로 잠겼는지. 감사 로그 `ACCOUNT_LOCKED`를 남길지 정합니다 (AUD-01)
 */
data class LoginFailureResult(
    val account: Account,
    val locked: Boolean,
)
