package com.dozycoffee.auth.server.domain

import com.dozycoffee.auth.core.Jwks
import java.time.Duration

/**
 * 정책 값 (domain.md §2). 이름은 정책 이름(`policy.xxx`)을 따릅니다.
 *
 * 값을 바꿀 일이 생기면 `dozy.auth.policy.*` 속성으로 노출합니다 (configuration.md §1).
 *
 * 서비스만 쓰는 값(`policy.clock-skew`, `policy.jwks-refetch-min-interval`)은 스타터에 있고,
 * 배치 일정인 `policy.cleanup-schedule`은 해당 작업에서, 아직 정하지 않은 `policy.rate-limit-password-confirm`은
 * 비밀번호 변경·파트너 탈퇴 작업에서 추가합니다.
 */
object AuthPolicy {
    /** `policy.access-token-ttl`. access token, system token 공통. */
    val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(10)

    /** `policy.refresh-idle-ttl`. refresh 세션은 갱신할 때마다 이만큼 연장됩니다. */
    val REFRESH_IDLE_TTL: Duration = Duration.ofHours(8)

    /** `policy.refresh-absolute-ttl`. 최초 로그인 기준이며 연장되지 않습니다. */
    val REFRESH_ABSOLUTE_TTL: Duration = Duration.ofDays(7)

    /** `policy.rotation-grace`. 교체 직후 직전 refresh token을 동시 요청으로 보는 시간 (SES-03). */
    val ROTATION_GRACE: Duration = Duration.ofSeconds(30)

    /** `policy.login-lock-threshold`. 이 횟수만큼 연속으로 로그인에 실패하면 잠급니다 (LGN-01). */
    const val LOGIN_LOCK_THRESHOLD: Int = 5

    /** `policy.login-lock-duration`. 로그인 잠금 시간 (LGN-01). */
    val LOGIN_LOCK_DURATION: Duration = Duration.ofMinutes(15)

    /** `policy.password-min-length` (PWD-01). */
    const val PASSWORD_MIN_LENGTH: Int = 8

    /** `policy.password-max-length` (PWD-01). */
    const val PASSWORD_MAX_LENGTH: Int = 128

    /** `policy.invitation-ttl`. `EMPLOYEE_INVITATION` verification의 유효 시간 (VER-01). */
    val INVITATION_TTL: Duration = Duration.ofHours(72)

    /** `policy.signup-verification-ttl`. `SIGNUP_VERIFICATION` verification의 유효 시간 (VER-01). */
    val SIGNUP_VERIFICATION_TTL: Duration = Duration.ofHours(24)

    /** `policy.password-reset-ttl`. `PASSWORD_RESET` verification의 유효 시간 (VER-01). */
    val PASSWORD_RESET_TTL: Duration = Duration.ofMinutes(30)

    /** `policy.owner-transfer-ttl`. `OWNER_TRANSFER` verification의 유효 시간 (VER-01). */
    val OWNER_TRANSFER_TTL: Duration = Duration.ofHours(72)

    /** `policy.secret-bytes`. refresh token, verification 토큰, client secret의 난수 바이트 수 ([OpaqueSecret]). */
    const val SECRET_BYTES: Int = 32

    /** `policy.signing-key-size`. RSA 서명 키의 최소 비트 수. */
    const val SIGNING_KEY_SIZE: Int = 3072

    /** `policy.jwks-cache-max-age`. JWKS 응답의 `Cache-Control: max-age`. 서비스의 JWKS 캐시와 같은 값이라 auth-core에 둡니다. */
    val JWKS_CACHE_MAX_AGE: Duration = Jwks.CACHE_MAX_AGE

    /** `policy.session-retention`. 만료·폐기된 refresh 세션을 지우기 전까지 보관하는 기간 (AUD-05). */
    val SESSION_RETENTION: Duration = Duration.ofDays(30)

    /** `policy.verification-retention`. 만료·사용·무효화된 verification을 지우기 전까지 보관하는 기간 (AUD-05). */
    val VERIFICATION_RETENTION: Duration = Duration.ofDays(30)

    /** `policy.audit-retention`. 감사 로그 보관 기간 (AUD-05). 1년은 365일로 셉니다. */
    val AUDIT_RETENTION: Duration = Duration.ofDays(365)

    /** `policy.audit-query-max-range`. 감사 로그를 한 번에 조회할 수 있는 기간 상한 (AUD-04). */
    val AUDIT_QUERY_MAX_RANGE: Duration = Duration.ofDays(90)

    /** `policy.audit-query-default-range`. 감사 로그 조회 기간을 주지 않았을 때의 기간. */
    val AUDIT_QUERY_DEFAULT_RANGE: Duration = Duration.ofDays(7)

    /**
     * `policy.rate-limit-ip`. 인증 없이 호출하는 API의 클라이언트 IP 단위 한도 (api/conventions.md §8).
     * 카운터는 인스턴스 메모리에 있어 인스턴스마다 따로 셉니다 (ADR-0024).
     */
    val RATE_LIMIT_IP: RateLimit = RateLimit(capacity = 20, period = Duration.ofMinutes(1))

    /**
     * `policy.rate-limit-email`. 메일을 보내는 API(가입, 인증 메일 재발송, 비밀번호 찾기)의 받는 이메일 단위 한도.
     * 넘으면 같은 `202`로 응답하고 메일만 보내지 않습니다 (api/conventions.md §8). 인스턴스 단위입니다 (ADR-0024).
     */
    val RATE_LIMIT_EMAIL: RateLimit = RateLimit(capacity = 3, period = Duration.ofMinutes(10))
}
