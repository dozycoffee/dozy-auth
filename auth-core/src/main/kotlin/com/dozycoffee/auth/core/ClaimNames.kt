package com.dozycoffee.auth.core

/**
 * Auth가 정의한 access token claim 이름.
 *
 * `iss`, `sub`, `aud`, `iat`, `exp`, `jti` 같은 표준 claim은 JWT 라이브러리의 상수를 씁니다.
 */
public object ClaimNames {
    /** 계정 종류. 값은 [PrincipalType.claimValue]. */
    public const val PRINCIPAL_TYPE: String = "principalType"

    /** principal id. 값은 number. */
    public const val PRINCIPAL_ID: String = "principalId"

    /** `{audience}:{code}` 형식의 role 목록. 값은 string 배열. */
    public const val ROLES: String = "roles"

    /** refresh 세션 id. system token에는 없음. */
    public const val SID: String = "sid"
}
