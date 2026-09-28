package com.dozycoffee.auth.server.domain

import com.dozycoffee.auth.core.Jwks
import java.time.Duration

/**
 * 정책 값 (domain.md §2). 이름은 정책 이름(`policy.xxx`)을 따릅니다.
 *
 * 값을 바꿀 일이 생기면 `dozy.auth.policy.*` 속성으로 노출합니다 (configuration.md §1).
 */
object AuthPolicy {
    /** `policy.access-token-ttl`. access token, system token 공통. */
    val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(10)

    /** `policy.jwks-cache-max-age`. JWKS 응답의 `Cache-Control: max-age`. 서비스의 JWKS 캐시와 같은 값이라 auth-core에 둡니다. */
    val JWKS_CACHE_MAX_AGE: Duration = Jwks.CACHE_MAX_AGE

    /** `policy.signing-key-size`. RSA 서명 키의 최소 비트 수. */
    const val SIGNING_KEY_SIZE: Int = 3072
}
