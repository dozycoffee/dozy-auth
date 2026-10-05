package com.dozycoffee.auth.server.application.port.outbound.metrics

import com.dozycoffee.auth.core.Realm

/**
 * 운영 지표 기록 (configuration.md §10). 지표 이름과 태그 이름은 어댑터가 정합니다.
 *
 * - 값은 정해진 몇 가지만 넘깁니다 (realm, 에러 code, 아래 enum). 이메일, id, IP처럼 값이 끝없이 늘어나는 것은 넘기지 않습니다.
 * - 결과가 정해진 시점에 셉니다. 트랜잭션 커밋을 기다리지 않습니다.
 */
interface RecordMetricsPort {
    /** 로그인 성공. */
    fun loginSucceeded(realm: Realm)

    /** 로그인 실패. [reason]은 응답의 에러 code입니다 (예: `INVALID_CREDENTIALS`, `TOO_MANY_ATTEMPTS`). */
    fun loginFailed(
        realm: Realm,
        reason: String,
    )

    /** access token 발급. */
    fun tokenIssued(
        kind: TokenIssueKind,
        realm: Realm,
    )

    /** SES-03 refresh token 재사용 탐지로 세션을 폐기함. */
    fun refreshReuseDetected(realm: Realm)

    /** 요청 제한(api/conventions.md §8)에 걸림. */
    fun rateLimitRejected(limit: RateLimitKind)

    /** AUD-05 정리 배치가 [table]에서 [rows]행을 지우고 커밋함. 묶음마다 부릅니다. */
    fun cleanupDeleted(
        table: CleanupTarget,
        rows: Int,
    )

    /** AUD-05 정리 배치 한 번의 실행이 끝남. */
    fun cleanupFinished(outcome: CleanupOutcome)
}

/** access token을 발급한 경로. */
enum class TokenIssueKind {
    /** 로그인 */
    LOGIN,

    /** 토큰 갱신 */
    REFRESH,

    /** 서비스 토큰 발급 (system token) */
    CLIENT_CREDENTIALS,

    /** 개발용 토큰 (`local`·`dev` 전용) */
    DEV,
}

/** 정리 배치가 지우는 테이블 (data-model.md §5). */
enum class CleanupTarget {
    REFRESH_SESSION,
    VERIFICATION,
    AUDIT_LOG,
}

/** 정리 배치 실행 결과. */
enum class CleanupOutcome {
    SUCCESS,
    FAILURE,
}

/** 요청 제한의 종류 (architecture.md §9.4). */
enum class RateLimitKind {
    /** `policy.rate-limit-ip` */
    IP,

    /** `policy.rate-limit-email` */
    EMAIL,

    /** `policy.rate-limit-password-confirm` */
    PASSWORD_CONFIRM,
}
