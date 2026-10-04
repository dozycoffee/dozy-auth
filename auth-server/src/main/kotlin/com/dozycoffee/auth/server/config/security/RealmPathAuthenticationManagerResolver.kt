package com.dozycoffee.auth.server.config.security

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationManagerResolver
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

/**
 * `/realms/{realm}/...` 본인 API의 토큰 인증 (api/conventions.md §2). [provider]로 토큰을 검증한 뒤, `iss`의 realm이 경로의
 * `{realm}`과 다르면 검증 실패(`401 UNAUTHENTICATED`)로 처리합니다.
 *
 * 경로를 알아야 하는 검사라 디코더가 아니라 요청마다 인증 관리자를 고르는 이 단계에서 합니다.
 */
class RealmPathAuthenticationManagerResolver(
    private val provider: AuthenticationProvider,
) : AuthenticationManagerResolver<HttpServletRequest> {
    override fun resolve(request: HttpServletRequest): AuthenticationManager {
        val pathRealm = REALM_PATH.matcher(request).variables[REALM_VARIABLE]
        return AuthenticationManager { authentication ->
            val result = provider.authenticate(authentication)
            val principal = result?.principal as? AuthenticatedPrincipal
            // 실패 이유는 응답에 넣지 않고 debug 로그에만 남습니다. 토큰 원문은 남기지 않습니다 (SEC-03)
            if (principal == null || principal.realm.pathValue != pathRealm) {
                throw InvalidBearerTokenException("iss realm does not match the path realm")
            }
            result
        }
    }

    private companion object {
        const val REALM_VARIABLE = "realm"
        val REALM_PATH: PathPatternRequestMatcher = PathPatternRequestMatcher.withDefaults().matcher("/realms/{$REALM_VARIABLE}/**")
    }
}
