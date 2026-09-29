package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken

/**
 * 검증을 통과한 토큰의 인증 정보. principal은 [AuthenticatedPrincipal], credentials는 원본 [Jwt]입니다.
 */
public class DozyAuthenticationToken(
    jwt: Jwt,
    principal: AuthenticatedPrincipal,
    authorities: Collection<GrantedAuthority>,
) : AbstractOAuth2TokenAuthenticationToken<Jwt>(jwt, principal, jwt, authorities) {
    init {
        isAuthenticated = true
    }

    /** 인증된 주체. */
    public val authenticatedPrincipal: AuthenticatedPrincipal get() = principal as AuthenticatedPrincipal

    override fun getName(): String = authenticatedPrincipal.key.sub

    override fun getTokenAttributes(): Map<String, Any> = token.claims
}
