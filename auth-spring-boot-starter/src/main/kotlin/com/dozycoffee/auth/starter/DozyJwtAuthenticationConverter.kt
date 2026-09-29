package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.ClaimNames
import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 검증된 토큰을 [DozyAuthenticationToken]으로 바꿉니다 (starter.md §3).
 *
 * `roles` 중 이 서비스 audience의 role만 골라 prefix를 떼고 `ROLE_{code}` 권한으로 만듭니다.
 * 예: audience가 `wms`면 `wms:inbound_manager` → `ROLE_inbound_manager`, `catalog:menu_editor`는 무시.
 */
public class DozyJwtAuthenticationConverter(
    private val properties: DozyAuthProperties,
) : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        // 검증기(DozyTokenValidators)를 통과한 토큰만 들어오므로 claim은 모두 있습니다
        val type = PrincipalType.fromClaimValue(checkNotNull(jwt.getClaimAsString(ClaimNames.PRINCIPAL_TYPE)))
        val key = PrincipalKey(type, PrincipalKey.parseId(checkNotNull(jwt.getClaimAsString(ClaimNames.PRINCIPAL_ID))))
        val realm = checkNotNull(properties.acceptedIssuers[jwt.issuer?.toString()]) { "검증되지 않은 토큰입니다" }
        val roles =
            jwt
                .getClaimAsStringList(ClaimNames.ROLES)
                .orEmpty()
                .map(RoleCode::parse)
                .filter { it.audience == properties.audience }
                .map(RoleCode::code)
                .toSet()

        val principal = AuthenticatedPrincipal(key, realm, roles, jwt.getClaimAsString(ClaimNames.SID))
        return DozyAuthenticationToken(jwt, principal, roles.map { SimpleGrantedAuthority("$ROLE_PREFIX$it") })
    }

    private companion object {
        private const val ROLE_PREFIX = "ROLE_"
    }
}
