package com.dozycoffee.auth.server.application.port.inbound.auth

import com.dozycoffee.auth.core.Realm

/** refresh 쿠키의 세션을 폐기합니다 (api/auth.md 로그아웃, SES-06, SES-08). */
interface LogoutUseCase {
    /** 세션이 없거나 이미 만료·폐기됐어도 예외 없이 끝납니다 (SES-08). */
    fun logout(command: LogoutCommand)
}

/**
 * 로그아웃 요청.
 *
 * @property realm 요청 경로의 realm. 다른 realm의 세션은 폐기하지 않습니다
 * @property refreshToken `dozy_refresh` 쿠키 값. 없으면 `null`
 * @property ip 클라이언트 주소. 감사 로그에 남깁니다
 * @property userAgent 요청의 `User-Agent`
 */
data class LogoutCommand(
    val realm: Realm,
    val refreshToken: String?,
    val ip: String?,
    val userAgent: String?,
) {
    /** refresh token은 로그 금지 값이라 가립니다 (SEC-03). */
    override fun toString(): String = "LogoutCommand(realm=$realm, refreshToken=***)"
}
