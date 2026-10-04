package com.dozycoffee.auth.server.application.port.inbound.auth

import com.dozycoffee.auth.core.Realm

/** refresh 쿠키로 access token을 다시 받고 refresh token을 교체합니다 (api/auth.md 토큰 갱신, SES-03~SES-05). */
interface RefreshTokenUseCase {
    /**
     * @return 새 access token과 교체한 refresh token
     * @throws com.dozycoffee.auth.server.domain.session.TokenRotatedException 교체 후 `policy.rotation-grace` 이내의 직전 토큰
     * @throws com.dozycoffee.auth.server.domain.session.SessionRevokedException 재사용 탐지로 세션을 방금 폐기함
     * @throws com.dozycoffee.auth.server.domain.session.SessionExpiredException 쿠키 없음, 세션 없음·만료·폐기, 다른 realm의 세션,
     *   계정이 `ACTIVE`가 아님
     */
    fun refresh(command: RefreshTokenCommand): LoginResult
}

/**
 * 토큰 갱신 요청.
 *
 * @property realm 요청 경로의 realm. 세션의 realm과 다르면 `SESSION_EXPIRED`입니다
 * @property refreshToken `dozy_refresh` 쿠키 값. 없으면 `null`
 * @property ip 클라이언트 주소. 재사용 탐지 감사 로그에 남깁니다
 * @property userAgent 요청의 `User-Agent`
 */
data class RefreshTokenCommand(
    val realm: Realm,
    val refreshToken: String?,
    val ip: String?,
    val userAgent: String?,
) {
    /** refresh token은 로그 금지 값이라 가립니다 (SEC-03). */
    override fun toString(): String = "RefreshTokenCommand(realm=$realm, refreshToken=***)"
}
