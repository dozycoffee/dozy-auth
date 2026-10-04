package com.dozycoffee.auth.server.domain.client

import com.dozycoffee.auth.server.domain.AuthException

/**
 * system client 인증 실패 (api/internal.md 서비스 토큰 발급). client_id 또는 secret 불일치, `ACTIVE`가 아닌 client를 구분하지 않습니다.
 *
 * code는 OAuth 2.0 에러 이름(`invalid_client`)이며, 이 API의 컨트롤러가 OAuth 형식(`error`, `error_description`)으로 응답합니다.
 * 메시지에 client_id나 secret을 넣지 않습니다 (SEC-03).
 */
class InvalidClientException : AuthException("invalid_client", 401, "Client authentication failed")

/** 같은 `client_id`의 system client가 이미 있음 (api/admin.md system client 등록). */
class ClientIdDuplicatedException : AuthException("CLIENT_ID_DUPLICATED", 409, "같은 clientId의 system client가 이미 있습니다.")

/** 없는 system client, system client가 아닌 principal (api/admin.md secret 재발급). 어느 경우인지 구분하지 않습니다. */
class SystemClientNotFoundException : AuthException("NOT_FOUND", 404, "system client를 찾을 수 없습니다.")
