package com.dozycoffee.auth.core

/** access token(system token 포함) header의 고정 값. 발급과 검증 모두 이 값만 씁니다. */
public object AccessTokenFormat {
    /** `typ` header 값 (RFC 9068). */
    public const val TYPE: String = "at+jwt"

    /** `alg` header 값. 다른 알고리즘은 모두 거부합니다. */
    public const val ALGORITHM: String = "RS256"
}
