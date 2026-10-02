package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.domain.token.AccessTokenFactory
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestComponent
import java.time.Clock
import java.util.UUID

/** 서버의 서명 키로 access token을 발급합니다. 로그인을 거치지 않고 특정 주체·realm·role의 토큰이 필요할 때 씁니다. */
@TestComponent
class TestAccessTokens(
    @Autowired private val signToken: SignTokenPort,
    @Autowired private val issuerBaseUri: IssuerBaseUri,
    @Autowired private val clock: Clock,
) {
    /** @param realm 기본은 [principal]의 type이 속한 realm */
    fun issue(
        principal: PrincipalKey,
        roles: List<String> = emptyList(),
        realm: Realm = principal.type.realm,
        sessionId: String? = if (principal.type == PrincipalType.SYSTEM) null else UUID.randomUUID().toString(),
    ): String =
        signToken.sign(
            AccessTokenFactory.create(
                principal = principal,
                realm = realm,
                roles = roles.map(RoleCode::parse),
                sessionId = sessionId,
                issuerBaseUri = issuerBaseUri,
                issuedAt = clock.instant(),
                tokenId = UUID.randomUUID().toString(),
            ),
        )
}
