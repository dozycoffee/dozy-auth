package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.inbound.LoginCommand
import com.dozycoffee.auth.server.application.port.inbound.LoginResult
import com.dozycoffee.auth.server.application.port.inbound.LoginUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.RecordLoginFailurePort
import com.dozycoffee.auth.server.application.port.outbound.account.ResetLoginFailuresPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.credential.LoadPasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.crypto.VerifyPasswordPort
import com.dozycoffee.auth.server.application.port.outbound.jwt.SignTokenPort
import com.dozycoffee.auth.server.application.port.outbound.session.CreateRefreshSessionPort
import com.dozycoffee.auth.server.domain.AuthException
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.AccountSuspendedException
import com.dozycoffee.auth.server.domain.account.EmailNotVerifiedException
import com.dozycoffee.auth.server.domain.account.Employee
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.credential.InvalidCredentialsException
import com.dozycoffee.auth.server.domain.session.NewRefreshSession
import com.dozycoffee.auth.server.domain.token.AccessTokenFactory
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 직원 로그인 (api/auth.md 로그인). 순서는 LGN-01입니다.
 *
 * 로그인 실패(`INVALID_CREDENTIALS`), 상태로 인한 거부(`EMAIL_NOT_VERIFIED`, `ACCOUNT_SUSPENDED`)는 에러 응답이어도
 * 실패 횟수·잠금과 감사 기록이 남아야 하므로 `noRollbackFor`로 커밋합니다 (architecture.md §9.2).
 * 이 예외들은 상태 변경과 감사 기록을 마친 뒤 마지막에 던지고, 그 경로에는 커밋돼도 되는 변경만 둡니다.
 *
 * 잠긴 계정의 거부(`TOO_MANY_ATTEMPTS`)는 아무것도 바꾸지 않고 기록도 남기지 않습니다 (AUD-08).
 */
@Service
class LoginService(
    private val loadEmployee: LoadEmployeePort,
    private val loadPasswordCredential: LoadPasswordCredentialPort,
    private val verifyPassword: VerifyPasswordPort,
    private val recordLoginFailure: RecordLoginFailurePort,
    private val resetLoginFailures: ResetLoginFailuresPort,
    private val createRefreshSession: CreateRefreshSessionPort,
    private val loadPrincipalRoles: LoadPrincipalRolesPort,
    private val signToken: SignTokenPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val issuerBaseUri: IssuerBaseUri,
    private val clock: Clock,
) : LoginUseCase {
    @Transactional(
        noRollbackFor = [InvalidCredentialsException::class, EmailNotVerifiedException::class, AccountSuspendedException::class],
    )
    override fun login(command: LoginCommand): LoginResult {
        require(command.realm == Realm.INTERNAL) { "${command.realm.pathValue} realm 로그인은 아직 제공하지 않습니다" }
        val now = clock.instant()

        // LGN-01 2. realm에 맞는 profile에서 이메일로 조회. 형식이 틀린 이메일은 없는 계정과 같습니다 (LGN-02)
        val employee = emailOrNull(command.email)?.let(loadEmployee::findEmployeeByEmail)

        // LGN-01 1. 계정 잠금 (IP 요청 제한은 컨트롤러보다 앞의 요청 제한 필터가 먼저 확인합니다)
        employee?.account?.ensureNotLocked(now)

        // LGN-01 3. 비밀번호 검증. 계정이나 비밀번호가 없으면 가짜 해시로 검증해 응답 시간을 맞춥니다 (LGN-02)
        val hash = employee?.let { loadPasswordCredential.findPasswordHash(it.account.id) }
        val passwordMatches = verifyPassword.verify(command.password, hash)

        if (employee == null) {
            recordAuditLog.record(AuditEvent.loginFailedForUnknownAccount(now, command.realm, command.ip, command.userAgent))
            throw InvalidCredentialsException()
        }
        if (!passwordMatches) failWrongPassword(employee, command, now)

        // LGN-01 4. 상태 확인. 비밀번호가 맞았을 때만 상태별로 구분합니다 (LGN-03)
        when (employee.account.status) {
            AccountStatus.ACTIVE -> Unit
            AccountStatus.PENDING -> reject(employee, command, now, EmailNotVerifiedException())
            AccountStatus.SUSPENDED -> reject(employee, command, now, AccountSuspendedException())
            AccountStatus.DEACTIVATED -> reject(employee, command, now, InvalidCredentialsException())
        }

        // LGN-01 5. 실패 횟수 초기화, refresh 세션 생성(SES-01, SES-02), 토큰 발급
        return succeed(employee, command, now)
    }

    /** LGN-01 3. 실패 횟수를 원자적으로 늘리고, 기준에 도달해 잠기면 `ACCOUNT_LOCKED`도 남깁니다. */
    private fun failWrongPassword(
        employee: Employee,
        command: LoginCommand,
        now: Instant,
    ): Nothing {
        val result = recordLoginFailure.recordLoginFailure(employee.account.id, now)
        val reason = InvalidCredentialsException()
        audit(AuditAction.LOGIN_FAILED, employee, command, now, mapOf(REASON to reason.code))
        if (result?.locked == true) audit(AuditAction.ACCOUNT_LOCKED, employee, command, now)
        throw reason
    }

    /** LGN-03 비밀번호는 맞았지만 상태 때문에 거부합니다. 실패 횟수는 늘리지 않고 `LOGIN_FAILED`만 남깁니다 (AUD-08). */
    private fun reject(
        employee: Employee,
        command: LoginCommand,
        now: Instant,
        error: AuthException,
    ): Nothing {
        audit(AuditAction.LOGIN_FAILED, employee, command, now, mapOf(REASON to error.code))
        throw error
    }

    private fun succeed(
        employee: Employee,
        command: LoginCommand,
        now: Instant,
    ): LoginResult {
        val principalId = employee.account.id
        resetLoginFailures.resetLoginFailures(principalId, now)

        val refreshToken = OpaqueSecret.generate()
        val session =
            createRefreshSession.createSession(
                NewRefreshSession.start(principalId, command.realm, refreshToken.hash(), command.userAgent, command.ip, now),
            )

        val claims =
            AccessTokenFactory.create(
                principal = PrincipalKey(employee.account.type, principalId),
                realm = command.realm,
                roles = loadPrincipalRoles.findRoleCodes(principalId),
                sessionId = session.id.toString(),
                issuerBaseUri = issuerBaseUri,
                issuedAt = now,
                tokenId = UUID.randomUUID().toString(),
            )
        val accessToken = signToken.sign(claims)

        audit(AuditAction.LOGIN_SUCCEEDED, employee, command, now, mapOf(SESSION_ID to session.id.toString()))
        return LoginResult(
            accessToken = accessToken,
            expiresIn = AuthPolicy.ACCESS_TOKEN_TTL,
            refreshToken = refreshToken,
            refreshTokenMaxAge = session.remainingAbsoluteLifetime(now),
        )
    }

    private fun audit(
        action: AuditAction,
        employee: Employee,
        command: LoginCommand,
        now: Instant,
        detail: Map<String, Any?> = emptyMap(),
    ) {
        val principal = AuditActor(employee.account.id, employee.account.type)
        recordAuditLog.record(AuditEvent.login(now, action, principal, command.realm, command.ip, command.userAgent, detail))
    }

    private fun emailOrNull(value: String): Email? = runCatching { Email(value) }.getOrNull()

    private companion object {
        const val REASON = "reason"
        const val SESSION_ID = "sessionId"
    }
}
