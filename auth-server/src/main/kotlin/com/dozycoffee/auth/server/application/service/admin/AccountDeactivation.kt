package com.dozycoffee.auth.server.application.service.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.application.port.outbound.account.ChangeAccountStatusPort
import com.dozycoffee.auth.server.application.port.outbound.account.ScrubEmployeeProfilePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.RevokeRolePort
import com.dozycoffee.auth.server.application.port.outbound.client.ScrubSystemClientPort
import com.dozycoffee.auth.server.application.port.outbound.credential.DeletePasswordCredentialPort
import com.dozycoffee.auth.server.application.port.outbound.session.RevokeSessionsPort
import com.dozycoffee.auth.server.application.port.outbound.verification.InvalidateVerificationPort
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.InvalidAccountStateException
import com.dozycoffee.auth.server.domain.session.RevokeReason
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * ACC-04 비활성화 처리. 관리자의 계정 비활성화와 초대 취소(ACC-06)가 함께 씁니다.
 *
 * 호출한 UseCase의 트랜잭션 안에서 다음을 처리합니다. 권한 검사(GOV-02, GOV-03)와 감사 기록은 호출하는 쪽이 합니다.
 * - 상태를 `DEACTIVATED`로 바꾸고 `deactivated_at`을 남김
 * - 살아 있는 refresh 세션 폐기 (`ACCOUNT_DEACTIVATED`)
 * - `password_credential`, `principal_role` 삭제. `external_identity`는 고객 realm을 도입할 때 생기므로(data-model.md §3.12) 아직 없습니다
 * - 살아 있는 verification 무효화
 * - 개인정보 파기: 직원은 profile, system client는 `client_id`·`client_secret_hash`.
 *   파트너·고객 profile 테이블은 아직 없어 파기할 것이 없으며, 테이블을 추가할 때 여기에 더합니다
 * - `principal` 행은 남깁니다 (ACC-05)
 */
@Component
class AccountDeactivation(
    private val changeAccountStatus: ChangeAccountStatusPort,
    private val revokeSessions: RevokeSessionsPort,
    private val deletePasswordCredential: DeletePasswordCredentialPort,
    private val revokeRole: RevokeRolePort,
    private val invalidateVerification: InvalidateVerificationPort,
    private val scrubEmployeeProfile: ScrubEmployeeProfilePort,
    private val scrubSystemClient: ScrubSystemClientPort,
) {
    /**
     * [account]를 비활성화합니다. [account]는 이 트랜잭션에서 잠그고 읽은 값이어야 합니다.
     *
     * @return 이번에 폐기한 세션 수 (AUD-08 `detail.revokedSessions`)
     * @throws InvalidAccountStateException 이미 `DEACTIVATED` (ACC-01)
     */
    fun deactivate(
        account: Account,
        now: Instant,
    ): Int {
        val deactivated = account.deactivate(now)
        if (!changeAccountStatus.changeStatus(account.id, account.status, deactivated.status, now)) throw InvalidAccountStateException()

        val revokedSessions = revokeSessions.revokeAllSessions(account.id, RevokeReason.ACCOUNT_DEACTIVATED, now)
        deletePasswordCredential.deletePasswordCredential(account.id)
        revokeRole.revokeAll(account.id)
        invalidateVerification.invalidateAll(account.id, now)
        when (account.type) {
            PrincipalType.EMPLOYEE -> scrubEmployeeProfile.scrubEmployeeProfile(account.id, now)
            PrincipalType.SYSTEM -> scrubSystemClient.scrubSystemClient(account.id)
            PrincipalType.PARTNER, PrincipalType.CUSTOMER -> Unit
        }
        return revokedSessions
    }
}
