package com.dozycoffee.auth.server.domain.verification

import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.SecretHash
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * 저장된 verification 하나 (docs/data-model.md §3.6, ADR-0012). 토큰 원문은 없고 해시만 가집니다 (VER-02, SEC-01).
 *
 * **살아 있는 토큰**은 만료 전이고 소비·무효화되지 않았으며 시도 횟수가 남은 토큰입니다. 만료 시각이 되면 만료로 봅니다.
 * 살아 있지 않은 토큰은 이유와 관계없이 `VERIFICATION_EXPIRED`입니다 (VER-04).
 *
 * @property target 발송한 주소의 스냅샷 (VER-08). 계정의 이메일이 나중에 바뀌거나 파기돼도 그대로입니다
 * @property payload 목적별 추가 데이터. 비어 있으면 저장하지 않습니다 (`NULL`). 토큰 원문이나 비밀번호를 넣지 않습니다
 * @property attemptCount 검증 시도 횟수
 * @property maxAttempts 시도 횟수 상한. 링크 토큰은 `null`이라 횟수를 보지 않습니다
 */
data class Verification(
    val id: Long,
    val principalId: UUID,
    val purpose: VerificationPurpose,
    val method: VerificationMethod,
    val target: Email,
    val tokenHash: SecretHash,
    val payload: Map<String, String>,
    val attemptCount: Int,
    val maxAttempts: Int?,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val invalidatedAt: Instant?,
    val createdAt: Instant,
) {
    /** [now]에 만료됐는지. 만료 시각부터 만료입니다. */
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    /** 시도 횟수 상한이 있고 다 썼는지. 링크 토큰은 항상 `false`입니다. */
    val attemptsExhausted: Boolean
        get() = maxAttempts != null && attemptCount >= maxAttempts

    /** [now]에 살아 있는지 (VER-03, GOV-09의 "진행 중"). */
    fun isLive(now: Instant): Boolean = consumedAt == null && invalidatedAt == null && !isExpired(now) && !attemptsExhausted

    /**
     * VER-04 [purpose]의 토큰으로 [now]에 쓸 수 있는지 확인합니다. 다른 목적으로 발급한 토큰도 거부합니다.
     *
     * 확인만 하며 소비하지 않습니다 (VER-06 초대 조회). 소비는 동시 요청을 막기 위해 저장소가 원자적으로 처리합니다.
     *
     * @throws VerificationExpiredException 목적이 다르거나 살아 있지 않을 때
     */
    fun ensureUsable(
        purpose: VerificationPurpose,
        now: Instant,
    ) {
        if (this.purpose != purpose || !isLive(now)) throw VerificationExpiredException()
    }

    companion object {
        /**
         * VER-04 해시로 찾은 결과([found])가 [purpose]의 토큰으로 [now]에 쓸 수 있으면 돌려줍니다.
         * 없는 토큰도 만료된 토큰과 같게 거부해 어느 경우인지 드러내지 않습니다.
         *
         * @throws VerificationExpiredException 없거나, 목적이 다르거나, 살아 있지 않을 때
         */
        fun requireUsable(
            found: Verification?,
            purpose: VerificationPurpose,
            now: Instant,
        ): Verification {
            if (found == null) throw VerificationExpiredException()
            found.ensureUsable(purpose, now)
            return found
        }
    }
}

/**
 * 저장하기 전의 verification. [issue]로 만듭니다. 발송 방식과 시도 횟수 상한은 [purpose]의 정책을 따릅니다 (VER-01).
 *
 * @property expiresAt [createdAt]에서 목적별 유효 시간 뒤
 */
data class NewVerification(
    val principalId: UUID,
    val purpose: VerificationPurpose,
    val target: Email,
    val tokenHash: SecretHash,
    val payload: Map<String, String>,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    val method: VerificationMethod
        get() = purpose.method

    val maxAttempts: Int?
        get() = purpose.maxAttempts

    companion object {
        private val DEFAULT_RANDOM = SecureRandom()

        /**
         * VER-01, VER-02 새 토큰을 발급합니다. 원문은 결과에만 있고 저장할 값에는 해시만 담습니다.
         * 같은 `(principal, purpose)`의 이전 토큰 무효화(VER-03)는 저장할 때 함께 처리합니다.
         *
         * @param target 발송할 주소 (VER-08)
         * @param now 발급 시각. 호출하는 쪽이 `Clock`에서 읽어 넘깁니다
         */
        fun issue(
            principalId: UUID,
            purpose: VerificationPurpose,
            target: Email,
            now: Instant,
            payload: Map<String, String> = emptyMap(),
            random: SecureRandom = DEFAULT_RANDOM,
        ): IssuedVerification {
            val token = OpaqueSecret.generate(random)
            val verification =
                NewVerification(
                    principalId = principalId,
                    purpose = purpose,
                    target = target,
                    tokenHash = token.hash(),
                    payload = payload,
                    expiresAt = now.plus(purpose.ttl),
                    createdAt = now,
                )
            return IssuedVerification(token, verification)
        }
    }
}

/**
 * 발급 결과. [token] 원문은 메일 링크로 한 번만 내보내고 저장하지 않습니다 (SEC-01).
 * [toString]에도 원문이 나오지 않습니다 (SEC-03).
 */
data class IssuedVerification(
    val token: OpaqueSecret,
    val verification: NewVerification,
)
