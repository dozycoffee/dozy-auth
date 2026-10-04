package com.dozycoffee.auth.server.domain.session

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.support.SessionFixtures.CURRENT_TOKEN_HASH
import com.dozycoffee.auth.server.support.SessionFixtures.LOGIN_AT
import com.dozycoffee.auth.server.support.SessionFixtures.PRINCIPAL_ID
import com.dozycoffee.auth.server.support.SessionFixtures.ROTATED_AT
import com.dozycoffee.auth.server.support.SessionFixtures.rotatedSession
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** refresh 세션의 생성과 만료 연장 (domain.md SES-01, SES-03, data-model.md §3.10). 시간은 `AuthPolicy`에서 가져옵니다. */
class RefreshSessionTest {
    @Test
    fun `SES-01 로그인하면 유휴 만료와 최초 로그인 기준 절대 만료를 정함`() {
        val session = NewRefreshSession.start(PRINCIPAL_ID, Realm.INTERNAL, CURRENT_TOKEN_HASH, "DozyApp/1.0", "203.0.113.7", LOGIN_AT)

        assertEquals(
            NewRefreshSession(
                principalId = PRINCIPAL_ID,
                realm = Realm.INTERNAL,
                tokenHash = CURRENT_TOKEN_HASH,
                createdAt = LOGIN_AT,
                expiresAt = LOGIN_AT.plus(AuthPolicy.REFRESH_IDLE_TTL),
                absoluteExpiresAt = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL),
                userAgent = "DozyApp/1.0",
                ip = "203.0.113.7",
            ),
            session,
        )
    }

    @Test
    fun `컬럼 길이를 넘는 User-Agent는 잘라서 기록`() {
        val session = NewRefreshSession.start(PRINCIPAL_ID, Realm.INTERNAL, CURRENT_TOKEN_HASH, "a".repeat(300), null, LOGIN_AT)

        assertEquals("a".repeat(255), session.userAgent)
    }

    @Test
    fun `SES-03 갱신하면 만료를 갱신 시각에서 유휴 만료 시간만큼 연장`() {
        assertEquals(ROTATED_AT.plus(AuthPolicy.REFRESH_IDLE_TTL), rotatedSession().extendedExpiresAt(ROTATED_AT))
    }

    @Test
    fun `SES-03 만료 연장은 절대 만료를 넘지 않음`() {
        val absolute = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL)
        val nearAbsolute = absolute.minus(Duration.ofHours(1))

        assertEquals(absolute, rotatedSession().extendedExpiresAt(nearAbsolute))
    }

    @Test
    fun `쿠키 수명은 절대 만료까지 남은 시간이며 1초 미만은 올림`() {
        val absolute = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL)

        assertEquals(AuthPolicy.REFRESH_ABSOLUTE_TTL, rotatedSession().remainingAbsoluteLifetime(LOGIN_AT))
        assertEquals(Duration.ofSeconds(10), rotatedSession().remainingAbsoluteLifetime(absolute.minusSeconds(10).plusNanos(1_000)))
    }

    @Test
    fun `절대 만료가 지났으면 쿠키 수명은 0`() {
        val absolute = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL)

        assertEquals(Duration.ZERO, rotatedSession().remainingAbsoluteLifetime(absolute.plusSeconds(1)))
    }

    @Test
    fun `만료 시각이 절대 만료 시각을 넘는 세션은 만들 수 없음`() {
        val absolute = LOGIN_AT.plus(AuthPolicy.REFRESH_ABSOLUTE_TTL)

        assertFailsWith<IllegalArgumentException> { rotatedSession(expiresAt = absolute.plusNanos(1), absoluteExpiresAt = absolute) }
    }
}
