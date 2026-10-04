package com.dozycoffee.auth.server.application.port.inbound

import java.time.Duration

/** 로그인 없이 원하는 주체와 role의 access token을 발급합니다 (api/dev.md 개발용 토큰 발급). `local`·`dev` 전용입니다. */
interface IssueDevTokenUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.token.InvalidTokenRequestException realm·principal type 조합 위반(DOM-01),
     *   principal id·role 형식 오류(DOM-03), 파트너에게 role 지정(DOM-04)
     */
    fun issue(command: IssueDevTokenCommand): IssuedDevToken
}

/**
 * 요청 값 그대로입니다. 형식과 조합은 UseCase가 검사합니다.
 *
 * @property realm `internal`, `partner`
 * @property principalType DOM-01의 조합만 허용
 * @property principalId 소문자·하이픈 포함 정규형 UUID. 계정이 있는지는 확인하지 않음
 * @property roles `{audience}:{code}` 목록
 */
data class IssueDevTokenCommand(
    val realm: String,
    val principalType: String,
    val principalId: String,
    val roles: List<String>,
)

/**
 * 발급한 개발용 토큰. refresh token은 없습니다.
 *
 * @property accessToken 서명한 JWT. 로그에 남지 않도록 [toString]은 가립니다 (SEC-03)
 * @property expiresIn 토큰 수명 (`policy.access-token-ttl`)
 */
class IssuedDevToken(
    val accessToken: String,
    val expiresIn: Duration,
) {
    override fun toString(): String = "IssuedDevToken(accessToken=***, expiresIn=$expiresIn)"
}
