package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AccessTokenFormat
import com.dozycoffee.auth.core.ClaimNames
import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwtTypeValidator
import java.time.Clock
import java.time.Duration

/**
 * token.md §6의 3, 6~9 검증기. 서명(2, 4, 5)은 [DozyJwtDecoders]의 processor가 먼저 확인합니다.
 *
 * 실패 이유는 [OAuth2Error.description]에만 담고 응답에는 넣지 않습니다 (starter.md §5).
 */
internal object DozyTokenValidators {
    fun create(
        properties: DozyAuthProperties,
        clock: Clock,
    ): OAuth2TokenValidator<Jwt> =
        DelegatingOAuth2TokenValidator(
            listOf(
                // 3. typ
                JwtTypeValidator(AccessTokenFormat.TYPE),
                // 6. exp, iat
                JwtTimestampValidator(properties.clockSkew).apply {
                    setAllowEmptyExpiryClaim(false)
                    setClock(clock)
                },
                issuedAt(properties.clockSkew, clock),
                // 7. iss
                OAuth2TokenValidator { jwt -> check(jwt.issuer?.toString() in properties.acceptedIssuers) { "iss is not accepted" } },
                // 8. aud
                OAuth2TokenValidator { jwt ->
                    check(properties.audience in jwt.audience.orEmpty()) { "aud does not contain this service" }
                },
                // 9. principalType, principalId, sub, realm 조합(DOM-01), roles 형식
                OAuth2TokenValidator { jwt -> principalError(jwt, properties).let { error -> check(error == null) { error.orEmpty() } } },
            ),
        )

    /** `iat`는 필수이고 미래일 수 없습니다. Spring의 `JwtIssuedAtValidator`는 과거 쪽도 오차 범위로 막아 쓰지 않습니다. */
    private fun issuedAt(
        clockSkew: Duration,
        clock: Clock,
    ) = OAuth2TokenValidator<Jwt> { jwt ->
        val issuedAt = jwt.issuedAt
        check(issuedAt != null && !issuedAt.isAfter(clock.instant().plus(clockSkew))) { "iat is missing or in the future" }
    }

    private fun principalError(
        jwt: Jwt,
        properties: DozyAuthProperties,
    ): String? {
        val type =
            jwt.getClaimAsString(ClaimNames.PRINCIPAL_TYPE)?.let(PrincipalType::fromClaimValueOrNull)
                ?: return "principalType is missing or unknown"
        val id = jwt.getClaimAsString(ClaimNames.PRINCIPAL_ID)?.let(PrincipalKey::parseIdOrNull) ?: return "principalId is not a UUID"
        if (jwt.subject != PrincipalKey(type, id).sub) return "sub does not match principalType and principalId"

        val realm = properties.acceptedIssuers[jwt.issuer?.toString()] ?: return "iss is not accepted"
        if (!realm.allows(type)) return "DOM-01 ${realm.pathValue} realm does not allow ${type.claimValue}"

        val roles = jwt.claims[ClaimNames.ROLES]
        if (roles !is List<*> || roles.any { it !is String || RoleCode.parseOrNull(it) == null }) return "roles is not a list of role codes"
        return null
    }

    private fun check(
        valid: Boolean,
        reason: () -> String,
    ): OAuth2TokenValidatorResult =
        if (valid) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, reason(), null))
        }
}
