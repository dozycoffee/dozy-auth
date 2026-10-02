package com.dozycoffee.auth.server.application.port.outbound.verification

import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.Verification

/** VER-01, VER-03 발급한 verification을 저장합니다. 토큰은 `NewVerification.issue`로 만듭니다. */
interface IssueVerificationPort {
    /**
     * 같은 `(principal, purpose)`에서 아직 소비·무효화되지 않은 토큰을 [verification]의 발급 시각으로 무효화한 뒤 저장합니다 (VER-03).
     * 만료됐지만 무효화되지 않은 토큰도 무효화합니다. 같은 `(principal, purpose)`로 동시에 발급하면 나중에 저장한 토큰 하나만 살아 남습니다.
     */
    fun issue(verification: NewVerification): Verification
}
