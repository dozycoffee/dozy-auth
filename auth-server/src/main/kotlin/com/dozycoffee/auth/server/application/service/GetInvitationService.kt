package com.dozycoffee.auth.server.application.service

import com.dozycoffee.auth.server.application.port.inbound.GetInvitationUseCase
import com.dozycoffee.auth.server.application.port.inbound.InvitationSummary
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.Masking
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 초대 조회 (api/account.md). 확인만 하고 토큰을 소비하지 않으며(VER-06), 이메일과 전화번호는 마스킹합니다 (SEC-02).
 *
 * 이메일은 verification의 발송 주소(`target`)가 아니라 profile의 로그인 이메일입니다. 수락한 뒤 로그인할 ID를 보여 주기 위해서입니다.
 */
@Service
class GetInvitationService(
    private val loadVerification: LoadVerificationPort,
    private val loadEmployee: LoadEmployeePort,
    private val clock: Clock,
) : GetInvitationUseCase {
    @Transactional(readOnly = true)
    override fun getInvitation(token: String): InvitationSummary {
        val (verification, employee) = findInvitation(token, clock.instant(), loadVerification, loadEmployee)
        val profile = employee.profile
        return InvitationSummary(
            name = profile.name,
            maskedEmail = Masking.email(profile.email),
            maskedPhone = profile.phone?.let(Masking::phone),
            expiresAt = verification.expiresAt,
        )
    }
}
