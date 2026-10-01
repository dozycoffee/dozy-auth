package com.dozycoffee.auth.server.domain.session

import com.dozycoffee.auth.server.domain.AuthException

/** 교체 직후 직전 refresh token을 쓴 경우 (SES-03). 세션은 유지되고 앱은 한 번 재시도합니다. */
class TokenRotatedException : AuthException("TOKEN_ROTATED", 409, "토큰이 방금 교체되었습니다. 다시 시도해 주세요.")
