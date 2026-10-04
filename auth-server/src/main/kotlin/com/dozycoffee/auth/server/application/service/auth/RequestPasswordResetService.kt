package com.dozycoffee.auth.server.application.service.auth

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.inbound.auth.RequestPasswordResetCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.RequestPasswordResetUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.mail.PasswordResetMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.application.service.system.RateLimitService
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 비밀번호 찾기 (api/account.md 비밀번호 찾기, LGN-04, VER-01 `PASSWORD_RESET`, VER-03).
 *
 * 어떤 경우든 같은 `202`로 끝나고, 아래를 모두 만족할 때만 재설정 메일을 보냅니다.
 * 1. 이메일 형식이 맞음. 틀리면 없는 계정과 같습니다 (로그인의 LGN-02와 같음)
 * 2. 이메일 단위 요청 제한(`policy.rate-limit-email`, api/conventions.md §8) 안. 계정이 없어도 같은 순서로 셉니다
 * 3. realm에 맞는 profile에 그 이메일의 `ACTIVE` 계정이 있음. 로그인 실패로 잠긴(`locked_until`) 계정도 `ACTIVE`이므로 보냅니다.
 *    재설정하면 잠금이 풀립니다 (PWD-07)
 *
 * 메일을 보낼 때는 새 토큰을 발급하고(이전 재설정 토큰은 같은 트랜잭션에서 무효화, VER-03) 커밋 후 별도 스레드에서 보냅니다
 * (architecture.md §9.3). 그래서 응답 시간에는 발송이 들어가지 않습니다. 받는 주소는 입력값이 아니라 profile의 이메일입니다.
 *
 * 감사 로그는 남기지 않습니다. AUD-01의 `PASSWORD_RESET_REQUESTED`는 관리자의 발송이고, 본인 요청은 계정을 바꾸지 않으며
 * 누구나 보낼 수 있는 요청이기 때문입니다. 비밀번호가 실제로 바뀌면 `PASSWORD_RESET`이 남습니다.
 */
@Service
class RequestPasswordResetService(
    private val loadEmployee: LoadEmployeePort,
    private val rateLimit: RateLimitService,
    private val issueVerification: IssueVerificationPort,
    private val sendMail: SendMailPort,
    private val clock: Clock,
) : RequestPasswordResetUseCase {
    @Transactional
    override fun requestPasswordReset(command: RequestPasswordResetCommand) {
        require(command.realm == Realm.INTERNAL) { "${command.realm.pathValue} realm 비밀번호 찾기는 아직 제공하지 않습니다" }
        val email = runCatching { Email(command.email) }.getOrNull() ?: return
        val employee = loadEmployee.findEmployeeByEmail(email)

        // api/conventions.md §8 계정 존재 여부와 관계없이 셉니다. 넘으면 메일만 보내지 않습니다
        if (!rateLimit.tryAcquireMailSend(email)) return
        if (employee == null || employee.account.status != AccountStatus.ACTIVE) return

        val now = clock.instant()
        val to = employee.profile.email
        val issued = NewVerification.issue(employee.account.id, VerificationPurpose.PASSWORD_RESET, to, now)
        val saved = issueVerification.issue(issued.verification)
        sendMail.send(PasswordResetMail(to, command.realm, issued.token, saved.expiresAt))
    }
}
