package com.dozycoffee.auth.core

import java.time.Duration

/** JWKS 게시(Auth)와 조회(서비스)가 함께 지키는 값 (token.md §7). */
public object Jwks {
    /** JWKS 경로. 주소는 `{issuer-base}` 뒤에 붙습니다. */
    public const val PATH: String = "/.well-known/jwks.json"

    /**
     * `policy.jwks-cache-max-age`. Auth는 JWKS 응답의 `Cache-Control: max-age`로 쓰고, 서비스는 JWKS 캐시 유지 시간으로 씁니다.
     * 키 교체 때 새 키를 게시한 뒤 이 시간만큼 기다리는 근거입니다.
     */
    public val CACHE_MAX_AGE: Duration = Duration.ofSeconds(300)
}
