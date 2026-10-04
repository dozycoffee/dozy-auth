package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.adapter.inbound.web.RealmPaths
import com.dozycoffee.auth.server.application.port.inbound.auth.LoginCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.LoginResult
import com.dozycoffee.auth.server.application.port.inbound.auth.LoginUseCase
import com.dozycoffee.auth.server.application.port.inbound.auth.LogoutCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.LogoutUseCase
import com.dozycoffee.auth.server.application.port.inbound.auth.RefreshTokenCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.RefreshTokenUseCase
import com.dozycoffee.auth.server.domain.credential.RawPassword
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 로그인 세션 API (api/auth.md): 로그인, 토큰 갱신, 로그아웃.
 *
 * 토큰 갱신과 로그아웃은 refresh 쿠키로 인증합니다. `Origin` 검사(api/conventions.md §7)는 이 컨트롤러보다 앞의
 * 보안 필터(`RefreshCookieOriginFilter`)가 합니다.
 */
@RestController
class SessionController(
    private val login: LoginUseCase,
    private val refreshToken: RefreshTokenUseCase,
    private val logout: LogoutUseCase,
) {
    /** 로그인. 지금은 `internal`만 받고 다른 realm은 `404`입니다. */
    @PostMapping(LOGIN_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun login(
        @PathVariable realm: String,
        @Valid @RequestBody body: LoginRequest,
        request: HttpServletRequest,
    ): ResponseEntity<TokenResponse> {
        val loginRealm = RealmPaths.resolve(realm, LOGIN_REALMS)
        val client = ClientInfo.of(request)
        val result =
            login.login(
                LoginCommand(
                    realm = loginRealm,
                    email = checkNotNull(body.email),
                    password = RawPassword(checkNotNull(body.password)),
                    ip = client.ip,
                    userAgent = client.userAgent,
                ),
            )
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, RefreshCookie.of(loginRealm, result).toString())
            .body(TokenResponse.of(result))
    }

    /**
     * 토큰 갱신. 성공하면 교체한 refresh token으로 쿠키를 다시 설정합니다. 에러 응답(`401`, `409`)은 쿠키를 건드리지 않습니다
     * (쿠키를 삭제하는 응답은 로그아웃과 파트너 탈퇴뿐, api/conventions.md §6).
     */
    @PostMapping(REFRESH_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun refresh(
        @PathVariable realm: String,
        @CookieValue(RefreshCookie.NAME, required = false) cookie: String?,
        request: HttpServletRequest,
    ): ResponseEntity<TokenResponse> {
        val sessionRealm = RealmPaths.resolve(realm, SESSION_REALMS)
        val client = ClientInfo.of(request)
        val result = refreshToken.refresh(RefreshTokenCommand(sessionRealm, cookie, client.ip, client.userAgent))
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.SET_COOKIE, RefreshCookie.of(sessionRealm, result).toString())
            .body(TokenResponse.of(result))
    }

    /** 로그아웃. 세션이 없어도 성공이며(SES-08) 항상 쿠키를 삭제합니다. */
    @PostMapping(LOGOUT_PATH)
    fun logout(
        @PathVariable realm: String,
        @CookieValue(RefreshCookie.NAME, required = false) cookie: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val sessionRealm = RealmPaths.resolve(realm, SESSION_REALMS)
        val client = ClientInfo.of(request)
        logout.logout(LogoutCommand(sessionRealm, cookie, client.ip, client.userAgent))
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, RefreshCookie.cleared(sessionRealm).toString())
            .build()
    }

    companion object {
        const val LOGIN_PATH = "/realms/{realm}/login"
        const val REFRESH_PATH = "/realms/{realm}/token/refresh"
        const val LOGOUT_PATH = "/realms/{realm}/logout"

        /** refresh 쿠키로 인증하는 경로 (api/conventions.md §2). `Origin` 검사 대상입니다 (§7). */
        val REFRESH_COOKIE_PATHS: Array<String> = arrayOf(REFRESH_PATH, LOGOUT_PATH)

        /** 지금 로그인을 받는 realm. 파트너 로그인은 partner realm 작업에서 추가합니다. */
        private val LOGIN_REALMS = setOf(Realm.INTERNAL)

        /** 토큰 갱신·로그아웃을 받는 realm (api/auth.md). 세션의 realm과 다르면 갱신은 `SESSION_EXPIRED`입니다. */
        private val SESSION_REALMS = setOf(Realm.INTERNAL, Realm.PARTNER)
    }
}

/** 로그인 요청. 비밀번호는 로그에 남기지 않도록 [toString]에서 가립니다 (SEC-03). */
data class LoginRequest(
    @field:NotBlank val email: String?,
    @field:NotBlank val password: String?,
) {
    override fun toString(): String = "LoginRequest(email=***, password=***)"
}

/** 로그인·토큰 갱신 응답 본문 (api/auth.md). */
data class TokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
) {
    override fun toString(): String = "TokenResponse(accessToken=***, tokenType=$tokenType, expiresIn=$expiresIn)"

    companion object {
        fun of(result: LoginResult): TokenResponse = TokenResponse(result.accessToken, "Bearer", result.expiresIn.seconds)
    }
}

/** refresh 쿠키 (api/conventions.md §6). `Domain`은 설정하지 않습니다. */
internal object RefreshCookie {
    const val NAME = "dozy_refresh"

    /** 로그인·토큰 갱신이 설정하는 쿠키. `Max-Age`는 세션의 남은 절대 만료 시간입니다. */
    fun of(
        realm: Realm,
        result: LoginResult,
    ): ResponseCookie = builder(realm, result.refreshToken.value).maxAge(result.refreshTokenMaxAge).build()

    /** 로그아웃이 쿠키를 지우는 값. 같은 속성에 빈 값, `Max-Age=0`입니다. */
    fun cleared(realm: Realm): ResponseCookie = builder(realm, "").maxAge(0).build()

    private fun builder(
        realm: Realm,
        value: String,
    ): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie
            .from(NAME, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/realms/${realm.pathValue}")
}
