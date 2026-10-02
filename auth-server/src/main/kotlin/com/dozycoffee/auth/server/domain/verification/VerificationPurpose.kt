package com.dozycoffee.auth.server.domain.verification

import com.dozycoffee.auth.server.domain.AuthPolicy
import java.time.Duration

/**
 * VER-01 verification의 목적과 목적별 정책. 소비할 때의 동작은 목적마다 UseCase가 처리합니다.
 *
 * `EMAIL_CHANGE`는 유효 시간이 정해지지 않은 추후 기능이라(ACC-07) 넣지 않습니다. DB의 `CHECK` 제약에는 이미 있으므로
 * 도입할 때 여기에 값만 추가합니다.
 *
 * @property method 발송 방식
 * @property ttl 발급 시각부터 유효한 시간
 * @property maxAttempts 검증 시도 횟수 상한. 링크 토큰은 `null`이며 지금 목적은 모두 링크 토큰입니다 (data-model.md §3.6)
 */
enum class VerificationPurpose(
    val method: VerificationMethod,
    val ttl: Duration,
    val maxAttempts: Int?,
) {
    /** 직원 초대. 소비하면 `password_credential` 생성, `ACTIVE` 전환. */
    EMPLOYEE_INVITATION(VerificationMethod.EMAIL, AuthPolicy.INVITATION_TTL, maxAttempts = null),

    /** 파트너 가입 인증. 소비하면 `ACTIVE` 전환. */
    SIGNUP_VERIFICATION(VerificationMethod.EMAIL, AuthPolicy.SIGNUP_VERIFICATION_TTL, maxAttempts = null),

    /** 비밀번호 찾기. 소비하면 PWD-07. */
    PASSWORD_RESET(VerificationMethod.EMAIL, AuthPolicy.PASSWORD_RESET_TTL, maxAttempts = null),

    /** owner 양도. **대상** principal에 발급하며 소비하면 GOV-09. */
    OWNER_TRANSFER(VerificationMethod.EMAIL, AuthPolicy.OWNER_TRANSFER_TTL, maxAttempts = null),
}

/** verification 발송 방식 (data-model.md §3.6). `SMS`는 추후입니다. */
enum class VerificationMethod {
    EMAIL,
}
