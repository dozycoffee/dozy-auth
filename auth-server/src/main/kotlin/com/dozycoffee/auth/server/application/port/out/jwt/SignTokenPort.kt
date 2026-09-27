package com.dozycoffee.auth.server.application.port.out.jwt

import com.dozycoffee.auth.server.domain.token.AccessTokenClaims

/** access token에 서명합니다 (token.md §2). */
interface SignTokenPort {
    /** 활성 서명 키로 서명한 JWT 문자열을 돌려줍니다. */
    fun sign(claims: AccessTokenClaims): String
}
