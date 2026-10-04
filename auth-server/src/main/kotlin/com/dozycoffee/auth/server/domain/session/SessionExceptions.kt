package com.dozycoffee.auth.server.domain.session

import com.dozycoffee.auth.server.domain.AuthException

/** refresh 세션이 없거나 만료·폐기됨 (SES-03), 또는 갱신할 때 계정이 `ACTIVE`가 아님 (SES-05). */
class SessionExpiredException : AuthException("SESSION_EXPIRED", 401, "세션이 만료되었습니다. 다시 로그인해 주세요.")

/** 재사용 탐지로 refresh 세션을 방금 폐기함 (SES-03). */
class SessionRevokedException : AuthException("SESSION_REVOKED", 401, "세션이 폐기되었습니다. 다시 로그인해 주세요.")

/** 교체 직후 직전 refresh token을 쓴 경우 (SES-03). 세션은 유지되고 앱은 한 번 재시도합니다. */
class TokenRotatedException : AuthException("TOKEN_ROTATED", 409, "토큰이 방금 교체되었습니다. 다시 시도해 주세요.")
