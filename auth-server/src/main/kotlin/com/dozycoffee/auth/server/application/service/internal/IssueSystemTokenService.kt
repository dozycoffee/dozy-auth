package com.dozycoffee.auth.server.application.service.internal

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.inbound.internal.IssueSystemTokenCommand
import com.dozycoffee.auth.server.application.port.inbound.internal.IssueSystemTokenUseCase
import com.dozycoffee.auth.server.application.port.inbound.internal.IssuedSystemToken
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.client.LoadSystemClientPort
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.application.port.outbound.metrics.RecordMetricsPort
import com.dozycoffee.auth.server.application.port.outbound.metrics.TokenIssueKind
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.InvalidClientException
import com.dozycoffee.auth.server.domain.client.SystemClient
import com.dozycoffee.auth.server.domain.token.AccessTokenFactory
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * system token 발급 (token.md §8, api/internal.md 서비스 토큰 발급).
 *
 * 1. 제시한 secret을 먼저 해시하고, client가 없거나 secret이 지워졌어도 상수 시간 비교를 한 번 합니다 (SEC-05).
 * 2. secret이 맞으면 계정이 `system` 타입이고 `ACTIVE`인지 확인합니다 (CLI-04, ACC-04).
 * 3. 부여된 role로 `aud`·`roles`를 정하고 `sid` 없이 서명합니다 (token.md §4). refresh token은 없습니다 (CLI-05).
 *
 * 실패 원인은 구분하지 않고 모두 [InvalidClientException]입니다. 발급은 감사 action이 아니고, 지표로만 셉니다 (AUD-01, configuration.md §10).
 */
@Service
class IssueSystemTokenService(
    private val loadSystemClient: LoadSystemClientPort,
    private val loadAccount: LoadAccountPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val signToken: SignTokenPort,
    private val recordMetrics: RecordMetricsPort,
    private val issuerBaseUri: IssuerBaseUri,
    private val clock: Clock,
) : IssueSystemTokenUseCase {
    @Transactional(readOnly = true)
    override fun issue(command: IssueSystemTokenCommand): IssuedSystemToken {
        val presented = SecretHash.of(command.clientSecret)
        val client = ClientId.parseOrNull(command.clientId)?.let(loadSystemClient::findByClientId)
        val authenticated = SystemClient.authenticate(client, presented) ?: throw InvalidClientException()

        val account = loadAccount.findAccountById(authenticated.principalId)
        if (account == null || account.type != PrincipalType.SYSTEM || account.status != AccountStatus.ACTIVE) {
            throw InvalidClientException()
        }

        val claims =
            AccessTokenFactory.create(
                principal = PrincipalKey(PrincipalType.SYSTEM, account.id),
                realm = Realm.INTERNAL,
                roles = loadPrincipalRoles.findRoleCodes(account.id),
                sessionId = null,
                issuerBaseUri = issuerBaseUri,
                issuedAt = clock.instant(),
                tokenId = UUID.randomUUID().toString(),
            )
        val token = signToken.sign(claims)
        recordMetrics.tokenIssued(TokenIssueKind.CLIENT_CREDENTIALS, Realm.INTERNAL)
        return IssuedSystemToken(token, Duration.between(claims.issuedAt, claims.expiresAt))
    }
}
