package com.dozycoffee.auth.server.application.port.inbound.internal

import java.time.Duration

/** client credentials로 system token을 발급합니다 (token.md §8, api/internal.md 서비스 토큰 발급). */
interface IssueSystemTokenUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.client.InvalidClientException client_id 또는 secret 불일치, `ACTIVE`가 아닌 client
     */
    fun issue(command: IssueSystemTokenCommand): IssuedSystemToken
}

/**
 * `client_secret_basic`으로 받은 client 인증 정보. 형식은 검사하지 않으며, 형식이 틀린 값은 인증 실패로 처리됩니다.
 * 로그에 남지 않도록 [toString]은 secret을 가립니다 (SEC-03).
 */
class IssueSystemTokenCommand(
    val clientId: String,
    val clientSecret: String,
) {
    override fun toString(): String = "IssueSystemTokenCommand(clientId=$clientId, clientSecret=***)"
}

/**
 * 발급한 system token. refresh token은 없습니다 (CLI-05).
 *
 * @property accessToken 서명한 JWT. 로그에 남지 않도록 [toString]은 가립니다 (SEC-03)
 * @property expiresIn 토큰 수명 (`policy.access-token-ttl`)
 */
class IssuedSystemToken(
    val accessToken: String,
    val expiresIn: Duration,
) {
    override fun toString(): String = "IssuedSystemToken(accessToken=***, expiresIn=$expiresIn)"
}
