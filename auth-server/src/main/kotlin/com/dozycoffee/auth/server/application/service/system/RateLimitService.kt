package com.dozycoffee.auth.server.application.service.system

import com.dozycoffee.auth.server.application.port.inbound.system.CheckClientRateLimitUseCase
import com.dozycoffee.auth.server.application.port.outbound.ratelimit.ConsumeRateLimitPort
import com.dozycoffee.auth.server.application.port.outbound.ratelimit.RateLimitKey
import com.dozycoffee.auth.server.application.port.outbound.ratelimit.RateLimitResult
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.RateLimit
import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 요청 제한 (api/conventions.md §8). 용도마다 한도를 넘었을 때의 동작이 달라 메서드를 나눕니다.
 *
 * | 용도 | 메서드 | 한도 | 넘으면 |
 * |---|---|---|---|
 * | 인증 없는 API | [check] (요청 제한 필터) | `policy.rate-limit-ip` | `429 TOO_MANY_ATTEMPTS` |
 * | 메일을 보내는 API | [tryAcquireMailSend] (UseCase) | `policy.rate-limit-email` | 같은 `202`, 메일만 보내지 않음 |
 * | 본인 확인용 비밀번호를 받는 API | [checkPasswordConfirmation] (UseCase) | `policy.rate-limit-password-confirm` | `429 TOO_MANY_ATTEMPTS` |
 *
 * 카운터는 인스턴스 메모리에 있습니다 (ADR-0024). 트랜잭션과 관계없이 호출한 순간 1회를 쓰고, 업무가 롤백돼도 되돌리지 않습니다.
 */
@Service
class RateLimitService(
    private val consumeRateLimit: ConsumeRateLimitPort,
) : CheckClientRateLimitUseCase {
    override fun check(clientIp: String?) {
        consume(RateLimitKey.ClientIp(clientIp ?: UNKNOWN_IP), AuthPolicy.RATE_LIMIT_IP)
    }

    /**
     * 메일을 보내는 UseCase(가입, 인증 메일 재발송, 비밀번호 찾기)가 메일을 보내기 직전에 호출합니다.
     *
     * `false`면 메일을 보내지 않고, 응답은 보냈을 때와 같은 `202`로 합니다. 예외를 던지지 않으므로 응답으로 계정 존재 여부나
     * 제한 여부가 드러나지 않습니다 (LGN-04). 계정이 없어 메일을 보내지 않는 경로에서도 호출해, 있는 계정과 같은 순서로 셉니다.
     *
     * @return 이 이메일로 메일을 보내도 되면 `true`
     */
    fun tryAcquireMailSend(email: Email): Boolean =
        consumeRateLimit.tryConsume(RateLimitKey.MailRecipient(email), AuthPolicy.RATE_LIMIT_EMAIL) is RateLimitResult.Allowed

    /**
     * 본인 확인용 비밀번호를 받는 UseCase(비밀번호 변경, 파트너 탈퇴)가 비밀번호를 검증하기 **전에** 호출합니다.
     * 한도를 넘으면 비밀번호를 검증하지 않고 `TooManyAttemptsException`입니다.
     *
     * 비밀번호가 맞았는지와 관계없이 호출할 때마다 1회를 씁니다. 성공한 확인도 셉니다 (api/conventions.md §8).
     */
    fun checkPasswordConfirmation(principalId: UUID) {
        consume(RateLimitKey.PasswordConfirmation(principalId), AuthPolicy.RATE_LIMIT_PASSWORD_CONFIRM)
    }

    private fun consume(
        key: RateLimitKey,
        limit: RateLimit,
    ) {
        val result = consumeRateLimit.tryConsume(key, limit)
        if (result is RateLimitResult.Limited) throw TooManyAttemptsException(result.retryAfter)
    }

    private companion object {
        const val UNKNOWN_IP = "unknown"
    }
}
