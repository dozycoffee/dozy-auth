package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.server.application.port.inbound.auth.AcceptInvitationCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.AcceptInvitationUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.ChangeAccountStatusPort
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.application.port.outbound.credential.CreatePasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.crypto.HashPasswordPort
import com.dozycoffee.auth.server.application.port.outbound.verification.ConsumeVerificationPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.credential.PasswordPolicy
import com.dozycoffee.auth.server.domain.verification.VerificationExpiredException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 초대 수락 (api/account.md, VER-01 `EMPLOYEE_INVITATION`). verification 소비, `ACTIVE` 전환(ACC-01), credential 생성,
 * 감사 기록(`INVITATION_ACCEPTED`)을 한 트랜잭션에서 처리하며, 어느 단계든 실패하면 모두 롤백됩니다.
 *
 * - 비밀번호 규칙(PWD-01~PWD-03) 검사와 해시(PWD-04)는 아무것도 바꾸기 전에 합니다. 느린 해시를 행 잠금 밖에서 하기 위해서입니다.
 * - 바꾸는 단계는 verification 소비가 먼저입니다. 같은 초대를 동시에 수락하면 늦은 요청의 소비는 앞 트랜잭션이 끝날 때까지 기다렸다가
 *   `false`가 되어 `VERIFICATION_EXPIRED`로 끝나므로, credential 기본 키 충돌 같은 500 없이 하나만 성공합니다.
 * - 상태 전환도 `PENDING`일 때만 바꾸는 원자적 변경이며, 그 사이 계정이 바뀌었으면 `VERIFICATION_EXPIRED`로 롤백합니다.
 * - 자동 로그인하지 않습니다. 앱은 로그인 화면으로 보냅니다.
 */
@Service
class AcceptInvitationService(
    private val loadVerification: LoadVerificationPort,
    private val loadEmployee: LoadEmployeePort,
    private val hashPassword: HashPasswordPort,
    private val consumeVerification: ConsumeVerificationPort,
    private val changeAccountStatus: ChangeAccountStatusPort,
    private val createPasswordCredential: CreatePasswordCredentialPort,
    private val recordAuditLog: RecordAuditLogPort,
    private val clock: Clock,
) : AcceptInvitationUseCase {
    @Transactional
    override fun accept(command: AcceptInvitationCommand) {
        val now = clock.instant()
        val (verification, employee) = findInvitation(command.token, now, loadVerification, loadEmployee)
        val account = employee.account

        PasswordPolicy.check(command.password, employee.profile.email)
        val hash = hashPassword.hash(command.password)

        // VER-04 살아 있을 때만 소비합니다. 동시에 수락하면 하나만 성공합니다
        if (!consumeVerification.consume(verification.id, now)) throw VerificationExpiredException()
        // ACC-01 초대 수락: PENDING → ACTIVE
        val activated = account.activate()
        if (!changeAccountStatus.changeStatus(account.id, account.status, activated.status, now)) throw VerificationExpiredException()
        createPasswordCredential.createPasswordCredential(account.id, hash, now)

        recordAuditLog.record(
            AuditEvent(
                occurredAt = now,
                action = AuditAction.INVITATION_ACCEPTED,
                actor = AuditActor(account.id, account.type),
                target = AuditTarget.principal(account.id),
                ip = command.ip,
                userAgent = command.userAgent,
            ),
        )
    }
}
