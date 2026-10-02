package com.dozycoffee.auth.server.application.port.outbound.ratelimit

import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.RateLimit
import java.time.Duration
import java.util.UUID

/**
 * 요청 제한 카운터 (api/conventions.md §8). 키마다 [RateLimit] 한도의 버킷이 있고, 호출할 때마다 1회를 씁니다.
 *
 * 카운터는 인스턴스 메모리에 있습니다 (ADR-0024). UseCase는 이 포트를 직접 쓰지 않고 `application/service/RateLimitService`의
 * 용도별 메서드를 씁니다. 한도를 넘었을 때의 응답이 용도마다 다르기 때문입니다 (예: 메일은 같은 `202`).
 */
interface ConsumeRateLimitPort {
    /** [key]의 버킷에서 1회를 씁니다. 같은 키라도 [limit]이 다르면 별개의 버킷입니다. */
    fun tryConsume(
        key: RateLimitKey,
        limit: RateLimit,
    ): RateLimitResult
}

/** 무엇 단위로 세는지. 종류가 다르면 값이 같아도 별개의 버킷입니다. */
sealed interface RateLimitKey {
    /** 인증 없는 API의 클라이언트 주소 (`policy.rate-limit-ip`). */
    data class ClientIp(
        val ip: String,
    ) : RateLimitKey

    /**
     * 메일을 받는 이메일 (`policy.rate-limit-email`). 대소문자를 구분하지 않습니다([Email.lookupKey]).
     * 어댑터는 주소 원문을 키로 보관하지 않고, [toString]도 주소를 가립니다.
     */
    data class MailRecipient(
        val email: Email,
    ) : RateLimitKey {
        override fun toString(): String = "MailRecipient(email=***)"
    }

    /** 본인 확인용 비밀번호를 받는 API(비밀번호 변경, 파트너 탈퇴)의 principal. */
    data class PasswordConfirmation(
        val principalId: UUID,
    ) : RateLimitKey
}

/** [ConsumeRateLimitPort.tryConsume]의 결과. */
sealed interface RateLimitResult {
    data object Allowed : RateLimitResult

    /** 한도를 넘음. [retryAfter] 뒤에 1회가 다시 채워집니다. */
    data class Limited(
        val retryAfter: Duration,
    ) : RateLimitResult
}
