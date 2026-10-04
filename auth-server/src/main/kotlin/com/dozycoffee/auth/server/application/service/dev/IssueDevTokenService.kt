package com.dozycoffee.auth.server.application.service.dev

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.inbound.dev.IssueDevTokenCommand
import com.dozycoffee.auth.server.application.port.inbound.dev.IssueDevTokenUseCase
import com.dozycoffee.auth.server.application.port.inbound.dev.IssuedDevToken
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.token.AccessTokenFactory
import com.dozycoffee.auth.server.domain.token.InvalidTokenRequestException
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 개발용 토큰 발급 (api/dev.md). 실제 발급과 같은 claim 조립([AccessTokenFactory])과 서명 키를 씁니다.
 *
 * - DB의 계정·role 등록 여부를 보지 않으므로 트랜잭션이 없습니다. 감사 로그와 refresh 세션도 없습니다.
 * - 세션이 없으므로 직원 토큰에도 `sid`를 넣지 않습니다 (token.md §3에서 `sid`는 선택).
 * - 엔드포인트는 `local`·`dev` 프로필에서만 등록하므로 다른 프로필에서는 호출되지 않습니다.
 */
@Service
class IssueDevTokenService(
    private val signToken: SignTokenPort,
    private val issuerBaseUri: IssuerBaseUri,
    private val clock: Clock,
) : IssueDevTokenUseCase {
    override fun issue(command: IssueDevTokenCommand): IssuedDevToken {
        val realm =
            Realm.fromPathValueOrNull(command.realm)?.takeIf { it in DEV_REALMS }
                ?: throw InvalidTokenRequestException("realm은 internal, partner 중 하나여야 합니다.")
        val type =
            PrincipalType.fromClaimValueOrNull(command.principalType)
                ?: throw InvalidTokenRequestException("principalType 값이 올바르지 않습니다.")
        // DOM-01 realm과 principal type 조합. customer는 받는 realm이 없어 여기서 거부됩니다
        if (!realm.allows(type)) throw InvalidTokenRequestException("realm과 principalType 조합이 올바르지 않습니다.")
        val id =
            PrincipalKey.parseIdOrNull(command.principalId)
                ?: throw InvalidTokenRequestException("principalId는 소문자·하이픈 포함 UUID여야 합니다.")
        // DOM-03 role 형식
        val roles =
            command.roles.map {
                RoleCode.parseOrNull(it) ?: throw InvalidTokenRequestException("roles는 {audience}:{code} 형식이어야 합니다.")
            }
        // DOM-04 파트너에게는 role을 부여하지 않음
        if (type == PrincipalType.PARTNER && roles.isNotEmpty()) {
            throw InvalidTokenRequestException("파트너 토큰에는 roles를 넣을 수 없습니다.")
        }

        val claims =
            AccessTokenFactory.create(
                principal = PrincipalKey(type, id),
                realm = realm,
                roles = roles,
                sessionId = null,
                issuerBaseUri = issuerBaseUri,
                issuedAt = clock.instant(),
                tokenId = UUID.randomUUID().toString(),
            )
        return IssuedDevToken(signToken.sign(claims), Duration.between(claims.issuedAt, claims.expiresAt))
    }

    private companion object {
        /** api/dev.md의 요청 realm. customer는 토큰 규칙이 정해지지 않아 받지 않습니다. */
        val DEV_REALMS = setOf(Realm.INTERNAL, Realm.PARTNER)
    }
}
