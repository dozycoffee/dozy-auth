package com.dozycoffee.auth.server.application.port.inbound

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.credential.RawPassword
import java.time.Duration

/** 이메일과 비밀번호로 로그인합니다 (api/auth.md 로그인, LGN-01~LGN-03, SES-01). */
interface LoginUseCase {
    /**
     * @throws com.dozycoffee.auth.server.domain.TooManyAttemptsException 계정이 잠겨 있을 때
     * @throws com.dozycoffee.auth.server.domain.credential.InvalidCredentialsException 계정 없음, 비밀번호 불일치, 비활성화된 계정
     * @throws com.dozycoffee.auth.server.domain.account.EmailNotVerifiedException 비밀번호는 맞았지만 `PENDING`
     * @throws com.dozycoffee.auth.server.domain.account.AccountSuspendedException 비밀번호는 맞았지만 `SUSPENDED`
     */
    fun login(command: LoginCommand): LoginResult
}

/**
 * 로그인 요청.
 *
 * @property email 입력한 이메일 그대로. 형식이 틀린 값은 없는 계정과 같게 처리합니다 (LGN-02)
 * @property ip 클라이언트 주소. 세션과 감사 로그에 남깁니다
 * @property userAgent 요청의 `User-Agent`
 */
data class LoginCommand(
    val realm: Realm,
    val email: String,
    val password: RawPassword,
    val ip: String?,
    val userAgent: String?,
) {
    /** 이메일은 개인정보, 비밀번호는 로그 금지 값이라 가립니다 (SEC-03). */
    override fun toString(): String = "LoginCommand(realm=$realm, email=***, password=***)"
}

/**
 * 로그인 결과. 토큰 갱신([RefreshTokenUseCase])도 같은 결과를 돌려줍니다 (api/auth.md: 로그인과 같은 본문).
 *
 * @property accessToken 서명한 access token
 * @property expiresIn access token 수명 (`policy.access-token-ttl`)
 * @property refreshToken refresh token 원문. 쿠키로 한 번만 내보냅니다 (SES-02)
 * @property refreshTokenMaxAge 쿠키 `Max-Age`. 세션의 남은 절대 만료 시간 (api/conventions.md §6)
 */
data class LoginResult(
    val accessToken: String,
    val expiresIn: Duration,
    val refreshToken: OpaqueSecret,
    val refreshTokenMaxAge: Duration,
) {
    override fun toString(): String = "LoginResult(accessToken=***, expiresIn=$expiresIn, refreshToken=***)"
}
