package com.dozycoffee.auth.server.domain.session

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.support.SessionFixtures.CURRENT_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.LOGIN_AT
import com.dozycoffee.auth.server.support.SessionFixtures.PREVIOUS_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.ROTATED_AT
import com.dozycoffee.auth.server.support.SessionFixtures.UNKNOWN_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.rotatedSession
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/**
 * refresh token 갱신 판정 (domain.md SES-03, api/auth.md 토큰 갱신).
 *
 * 세션은 `ROTATED_AT`에 한 번 교체한 상태이고, 시간 기준은 `AuthPolicy`에서 가져옵니다.
 */
class RefreshDecisionTest {
    private val inGrace: Instant = ROTATED_AT.plusSeconds(1)

    @Test
    fun `SES-03 살아 있는 세션의 현재 토큰이면 교체`() {
        val session = rotatedSession()

        assertEquals(RefreshDecision.Rotate(session), judge(session, CURRENT_TOKEN_HASH, inGrace))
    }

    @Test
    fun `SES-03 교체 직후 직전 토큰이면 TOKEN_ROTATED`() {
        assertEquals(RefreshDecision.TokenRotated, judge(rotatedSession(), PREVIOUS_TOKEN_HASH, inGrace))
    }

    @Test
    fun `SES-03 교체 후 유예 시간이 끝나는 시각까지는 직전 토큰이면 TOKEN_ROTATED`() {
        val graceEnd = ROTATED_AT.plus(AuthPolicy.ROTATION_GRACE)

        assertEquals(RefreshDecision.TokenRotated, judge(rotatedSession(), PREVIOUS_TOKEN_HASH, graceEnd))
    }

    @Test
    fun `SES-03 유예 시간이 지난 직전 토큰이면 재사용 탐지`() {
        val session = rotatedSession()
        val afterGrace = ROTATED_AT.plus(AuthPolicy.ROTATION_GRACE).plusNanos(1)

        assertEquals(RefreshDecision.ReuseDetected(session), judge(session, PREVIOUS_TOKEN_HASH, afterGrace))
    }

    @Test
    fun `SES-03 일치하는 세션이 없으면 SESSION_EXPIRED`() {
        assertEquals(RefreshDecision.Expired, RefreshDecision.judge(null, UNKNOWN_TOKEN_HASH, Realm.INTERNAL, inGrace))
    }

    @Test
    fun `SES-03 세션의 현재 토큰도 직전 토큰도 아니면 SESSION_EXPIRED`() {
        assertEquals(RefreshDecision.Expired, judge(rotatedSession(), UNKNOWN_TOKEN_HASH, inGrace))
    }

    @Test
    fun `SES-03 유휴 만료 직전까지는 현재 토큰으로 교체`() {
        val session = rotatedSession()

        assertEquals(RefreshDecision.Rotate(session), judge(session, CURRENT_TOKEN_HASH, session.expiresAt.minusNanos(1)))
    }

    @Test
    fun `SES-03 유휴 만료 시각이 되면 현재 토큰도 SESSION_EXPIRED`() {
        val session = rotatedSession()

        assertEquals(RefreshDecision.Expired, judge(session, CURRENT_TOKEN_HASH, session.expiresAt))
    }

    @Test
    fun `SES-03 절대 만료 시각이 되면 현재 토큰도 SESSION_EXPIRED`() {
        val absolute = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL)
        val session = rotatedSession(rotatedAt = absolute.minus(Duration.ofHours(1)), expiresAt = absolute)

        assertEquals(RefreshDecision.Expired, judge(session, CURRENT_TOKEN_HASH, absolute))
    }

    @Test
    fun `SES-03 폐기된 세션은 현재 토큰이어도 SESSION_EXPIRED`() {
        val session = rotatedSession(revokedAt = inGrace, revokeReason = RevokeReason.LOGOUT)

        assertEquals(RefreshDecision.Expired, judge(session, CURRENT_TOKEN_HASH, inGrace))
    }

    @Test
    fun `SES-03 폐기된 세션은 유예 시간이 지난 직전 토큰이어도 재사용 탐지 없이 SESSION_EXPIRED`() {
        val afterGrace = ROTATED_AT.plus(AuthPolicy.ROTATION_GRACE).plusNanos(1)
        val session = rotatedSession(revokedAt = afterGrace, revokeReason = RevokeReason.REUSE_DETECTED)

        assertEquals(RefreshDecision.Expired, judge(session, PREVIOUS_TOKEN_HASH, afterGrace))
    }

    @Test
    fun `SES-03 만료된 세션은 직전 토큰이어도 재사용 탐지 없이 SESSION_EXPIRED`() {
        val session = rotatedSession()

        assertEquals(RefreshDecision.Expired, judge(session, PREVIOUS_TOKEN_HASH, session.expiresAt))
    }

    @Test
    fun `요청한 realm의 세션이 아니면 현재 토큰이어도 SESSION_EXPIRED`() {
        val session = rotatedSession(realm = Realm.PARTNER)

        assertEquals(RefreshDecision.Expired, judge(session, CURRENT_TOKEN_HASH, inGrace, realm = Realm.INTERNAL))
    }

    @Test
    fun `요청한 realm의 세션이 아니면 유예 시간이 지난 직전 토큰이어도 재사용 탐지 없이 SESSION_EXPIRED`() {
        val session = rotatedSession(realm = Realm.PARTNER)
        val afterGrace = ROTATED_AT.plus(AuthPolicy.ROTATION_GRACE).plusNanos(1)

        assertEquals(RefreshDecision.Expired, judge(session, PREVIOUS_TOKEN_HASH, afterGrace, realm = Realm.INTERNAL))
    }

    @Test
    fun `SES-03 세션 판정 예외는 정해진 에러 코드와 상태`() {
        val expired = SessionExpiredException()
        val revoked = SessionRevokedException()
        val rotated = TokenRotatedException()

        assertEquals("SESSION_EXPIRED" to 401, expired.code to expired.status)
        assertEquals("SESSION_REVOKED" to 401, revoked.code to revoked.status)
        assertEquals("TOKEN_ROTATED" to 409, rotated.code to rotated.status)
    }

    private fun judge(
        session: RefreshSession,
        presented: SecretHash,
        now: Instant,
        realm: Realm = session.realm,
    ) = RefreshDecision.judge(session, presented, realm, now)
}
