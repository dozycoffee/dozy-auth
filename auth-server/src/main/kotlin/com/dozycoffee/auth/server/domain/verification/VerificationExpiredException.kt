package com.dozycoffee.auth.server.domain.verification

import com.dozycoffee.auth.server.domain.AuthException

/**
 * VER-04 만료, 사용, 무효화된 1회용 토큰. 없는 토큰, 다른 목적의 토큰도 같은 응답입니다.
 * 어느 경우인지 응답으로 구분하지 않습니다.
 */
class VerificationExpiredException : AuthException("VERIFICATION_EXPIRED", 410, "만료됐거나 이미 사용된 링크입니다.")
