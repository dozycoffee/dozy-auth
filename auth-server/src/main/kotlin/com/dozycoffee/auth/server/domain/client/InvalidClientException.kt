package com.dozycoffee.auth.server.domain.client

import com.dozycoffee.auth.server.domain.AuthException

/**
 * system client 인증 실패 (api/internal.md 서비스 토큰 발급). client_id 또는 secret 불일치, `ACTIVE`가 아닌 client를 구분하지 않습니다.
 *
 * code는 OAuth 2.0 에러 이름(`invalid_client`)이며, 이 API의 컨트롤러가 OAuth 형식(`error`, `error_description`)으로 응답합니다.
 * 메시지에 client_id나 secret을 넣지 않습니다 (SEC-03).
 */
class InvalidClientException : AuthException("invalid_client", 401, "Client authentication failed")
