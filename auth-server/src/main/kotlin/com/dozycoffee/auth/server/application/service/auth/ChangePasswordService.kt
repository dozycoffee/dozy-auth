package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.application.port.inbound.auth.ChangePasswordCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.ChangePasswordUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.credential.LoadPasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.credential.UpdatePasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.crypto.HashPasswordPort
import com.dozycoffee.auth.server.application.port.outbound.crypto.VerifyPasswordPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.service.system.RateLimitService
import com.dozycoffee.auth.server.domain.UnauthenticatedException
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.InvalidAccountStateException
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.credential.CurrentPasswordMismatchException
import com.dozycoffee.auth.server.domain.credential.PasswordPolicy
import com.dozycoffee.auth.server.domain.session.RevokeReason
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 비밀번호 변경 (api/auth.md 비밀번호 변경, PWD-06, PWD-08).
 *
 * 순서:
 * 1. 요청 제한 (`policy.rate-limit-password-confirm`, api/conventions.md §8). 비밀번호를 검증하기 전에, 결과와 관계없이 1회를 셉니다
 * 2. 계정 잠금(`FOR NO KEY UPDATE`)과 상태 확인. 같은 계정의 변경이 동시에 오면 차례로 처리합니다.
 *    없거나 비활성화된 계정은 `401 UNAUTHENTICATED`(내 정보와 같음), `ACTIVE`가 아니면 `409 INVALID_STATE`
 * 3. 현재 비밀번호 확인. 틀리면 `400 CURRENT_PASSWORD_MISMATCH` (PWD-08). 아무것도 바꾸지 않고 기록도 남기지 않습니다
 * 4. 새 비밀번호 규칙 (PWD-01~PWD-03). 어기면 `400 VALIDATION_FAILED`
 * 5. 해시 저장(`password_hash`, `changed_at`), 현재 세션(토큰의 `sid`)을 뺀 세션 폐기(`PASSWORD_CHANGED`),
 *    감사 로그 `PASSWORD_CHANGED`. 함께 폐기한 세션이 있으면 `detail.revokedSessions`(개수)를 남깁니다 (AUD-08)
 *
 * 토큰에 `sid`가 없으면(개발용 토큰) 남길 세션이 없으므로 모든 세션을 폐기합니다.
 */
@Service
class ChangePasswordService(
    private val rateLimit: RateLimitService,
    private val lockAccount: LockAccountPort,
    private val loadEmployee: LoadEmployeePort,
    private val loadPasswordCredential: LoadPasswordCredentialPort,
    private val verifyPassword: VerifyPasswordPort,
    private val hashPassword: HashPasswordPort,
    private val updatePasswordCredential: UpdatePasswordCredentialPort,
    private val revokeSessions: RevokeSessionsPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : ChangePasswordUseCase {
    @Transactional
    override fun changePassword(command: ChangePasswordCommand) {
        val principal = command.principal
        require(principal.type == PrincipalType.EMPLOYEE) { "${principal.type.claimValue} 비밀번호 변경은 아직 제공하지 않습니다" }

        // 1. api/conventions.md §8 비밀번호를 확인하기 전에 셉니다
        rateLimit.checkPasswordConfirmation(principal.id)

        // 2. 계정 잠금과 상태
        val account = lockAccount.lockAccountById(principal.id)
        val employee = loadEmployee.findEmployeeById(principal.id)
        if (account == null || employee == null || account.status == AccountStatus.DEACTIVATED) throw UnauthenticatedException()
        if (account.status != AccountStatus.ACTIVE) throw InvalidAccountStateException()

        // 3. PWD-08 현재 비밀번호 확인
        val currentHash = loadPasswordCredential.findPasswordHash(principal.id)
        if (!verifyPassword.verify(command.currentPassword, currentHash)) throw CurrentPasswordMismatchException()

        // 4. PWD-01~PWD-03
        PasswordPolicy.check(command.newPassword, employee.profile.email)

        // 5. PWD-06 저장, 현재 세션을 뺀 세션 폐기, 감사 기록
        val now = clock.instant()
        check(updatePasswordCredential.updatePasswordHash(principal.id, hashPassword.hash(command.newPassword), now)) {
            "확인한 비밀번호가 사라짐"
        }
        val revokedSessions =
            command.currentSessionId
                ?.let { revokeSessions.revokeAllSessionsExcept(principal.id, it, RevokeReason.PASSWORD_CHANGED, now) }
                ?: revokeSessions.revokeAllSessions(principal.id, RevokeReason.PASSWORD_CHANGED, now)

        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.PASSWORD_CHANGED,
                actor = AuditActor(principal.id, principal.type),
                target = AuditTarget.principal(principal.id),
                detail = if (revokedSessions > 0) mapOf(REVOKED_SESSIONS to revokedSessions) else emptyMap(),
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
    }

    private companion object {
        const val REVOKED_SESSIONS = "revokedSessions"
    }
}
