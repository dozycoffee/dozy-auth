package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.adapter.inbound.web.RealmPaths
import com.dozycoffee.auth.server.application.port.inbound.LoginCommand
import com.dozycoffee.auth.server.application.port.inbound.LoginResult
import com.dozycoffee.auth.server.application.port.inbound.LoginUseCase
import com.dozycoffee.auth.server.domain.credential.RawPassword
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** 로그인 세션 API (api/auth.md). 토큰 갱신과 로그아웃은 해당 작업에서 추가합니다. */
@RestController
class SessionController(
    private val login: LoginUseCase,
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

    companion object {
        const val LOGIN_PATH = "/realms/{realm}/login"

        /** 지금 로그인을 받는 realm. 파트너 로그인은 partner realm 작업에서 추가합니다. */
        private val LOGIN_REALMS = setOf(Realm.INTERNAL)
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

    fun of(
        realm: Realm,
        result: LoginResult,
    ): ResponseCookie =
        ResponseCookie
            .from(NAME, result.refreshToken.value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/realms/${realm.pathValue}")
            .maxAge(result.refreshTokenMaxAge)
            .build()
}
