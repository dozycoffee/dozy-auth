package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.server.application.port.inbound.auth.ResetPasswordCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.ResetPasswordUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.account.ResetLoginFailuresPort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.credential.UpdatePasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.crypto.HashPasswordPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.verification.ConsumeVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.credential.PasswordPolicy
import com.dozycoffee.auth.server.domain.session.RevokeReason
import com.dozycoffee.auth.server.domain.verification.Verification
import com.dozycoffee.auth.server.domain.verification.VerificationExpiredException
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 비밀번호 재설정 (api/account.md 비밀번호 재설정, PWD-07, VER-04). 초대 수락([AcceptInvitationService])과 같은 순서입니다.
 *
 * 1. 토큰 확인 (VER-04). 없거나 살아 있지 않거나 `PASSWORD_RESET`이 아니면 `VERIFICATION_EXPIRED`.
 *    토큰의 계정이 경로의 realm의 계정이 아니거나 `ACTIVE`가 아니어도 같은 응답입니다. 계정 상태를 드러내지 않고, 정지 중에는
 *    비밀번호를 바꾸지 못하게 하기 위해서입니다 (정지 해제 뒤 다시 요청)
 * 2. 새 비밀번호 규칙 (PWD-01~PWD-03)과 해시 (PWD-04). 아무것도 바꾸기 전이라 규칙을 어기면 토큰을 소비하지 않고, 같은 링크로 다시
 *    시도할 수 있습니다. 느린 해시를 행 잠금 밖에서 합니다
 * 3. 토큰 소비. 살아 있을 때만 소비하는 원자적 변경이라 같은 토큰을 동시에 쓰면 하나만 성공하고 나머지는 `VERIFICATION_EXPIRED`
 * 4. 계정 잠금(`FOR NO KEY UPDATE`)과 상태 재확인. 그 사이 정지·비활성화됐으면 `VERIFICATION_EXPIRED`로 모두 롤백합니다
 * 5. 해시 저장(`password_hash`, `changed_at`), 모든 세션 폐기(`PASSWORD_RESET`), 로그인 실패 횟수와 잠금 초기화 (PWD-07),
 *    감사 로그 `PASSWORD_RESET`. 함께 폐기한 세션이 있으면 `detail.revokedSessions`(개수)를 남깁니다 (AUD-08).
 *    행위자와 대상은 모두 그 계정입니다 (링크를 받은 본인)
 *
 * 자동 로그인하지 않습니다. 앱은 로그인 화면으로 보냅니다.
 */
@Service
class ResetPasswordService(
    private val loadVerification: LoadVerificationPort,
    private val loadEmployee: LoadEmployeePort,
    private val hashPassword: HashPasswordPort,
    private val consumeVerification: ConsumeVerificationPort,
    private val lockAccount: LockAccountPort,
    private val updatePasswordCredential: UpdatePasswordCredentialPort,
    private val revokeSessions: RevokeSessionsPort,
    private val resetLoginFailures: ResetLoginFailuresPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : ResetPasswordUseCase {
    @Transactional
    override fun resetPassword(command: ResetPasswordCommand) {
        val now = clock.instant()

        // 1. VER-04 토큰과 토큰의 계정
        val found = loadVerification.findByTokenHash(SecretHash.of(command.token))
        val verification = Verification.requireUsable(found, VerificationPurpose.PASSWORD_RESET, now)
        val employee = loadEmployee.findEmployeeById(verification.principalId)
        if (employee == null || employee.account.type.realm != command.realm || employee.account.status != AccountStatus.ACTIVE) {
            throw VerificationExpiredException()
        }

        // 2. PWD-01~PWD-03, PWD-04. 토큰을 소비하기 전에 검사합니다
        PasswordPolicy.check(command.newPassword, employee.profile.email)
        val hash = hashPassword.hash(command.newPassword)

        // 3. VER-04 살아 있을 때만 소비합니다. 동시에 쓰면 하나만 성공합니다
        if (!consumeVerification.consume(verification.id, now)) throw VerificationExpiredException()

        // 4. 정지·비활성화와 차례로 처리합니다
        val account = lockAccount.lockAccountById(employee.account.id)
        if (account == null || account.status != AccountStatus.ACTIVE) throw VerificationExpiredException()

        // 5. PWD-07 저장, 모든 세션 폐기, 실패 횟수와 잠금 초기화, 감사 기록
        if (!updatePasswordCredential.updatePasswordHash(account.id, hash, now)) throw VerificationExpiredException()
        val revokedSessions = revokeSessions.revokeAllSessions(account.id, RevokeReason.PASSWORD_RESET, now)
        resetLoginFailures.resetLoginFailures(account.id, now)

        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.PASSWORD_RESET,
                actor = AuditActor(account.id, account.type),
                target = AuditTarget.principal(account.id),
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
