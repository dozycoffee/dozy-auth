package com.dozycoffee.auth.server.domain

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * 정책 값(domain.md §2)을 고정합니다.
 *
 * 다른 테스트는 `AuthPolicy`를 참조하므로, 정책 값을 바꿀 때는 domain.md와 이 테스트만 고치면 됩니다.
 */
class AuthPolicyTest {
    @Test
    fun `access token은 10분 동안 유효`() {
        assertEquals(Duration.ofMinutes(10), AuthPolicy.ACCESS_TOKEN_TTL)
    }

    @Test
    fun `refresh 세션은 갱신마다 8시간 연장`() {
        assertEquals(Duration.ofHours(8), AuthPolicy.REFRESH_IDLE_TTL)
    }

    @Test
    fun `refresh 세션은 최초 로그인부터 최대 7일`() {
        assertEquals(Duration.ofDays(7), AuthPolicy.REFRESH_ABSOLUTE_TTL)
    }

    @Test
    fun `교체 직후 30초는 직전 토큰을 동시 요청으로 봄`() {
        assertEquals(Duration.ofSeconds(30), AuthPolicy.ROTATION_GRACE)
    }

    @Test
    fun `로그인은 5회 연속 실패하면 15분 잠금`() {
        assertEquals(5, AuthPolicy.LOGIN_LOCK_THRESHOLD)
        assertEquals(Duration.ofMinutes(15), AuthPolicy.LOGIN_LOCK_DURATION)
    }

    @Test
    fun `비밀번호는 8자 이상 128자 이하`() {
        assertEquals(8, AuthPolicy.PASSWORD_MIN_LENGTH)
        assertEquals(128, AuthPolicy.PASSWORD_MAX_LENGTH)
    }

    @Test
    fun `초대는 72시간, 가입 인증은 24시간, 비밀번호 재설정은 30분, owner 양도는 72시간 동안 유효`() {
        assertEquals(Duration.ofHours(72), AuthPolicy.INVITATION_TTL)
        assertEquals(Duration.ofHours(24), AuthPolicy.SIGNUP_VERIFICATION_TTL)
        assertEquals(Duration.ofMinutes(30), AuthPolicy.PASSWORD_RESET_TTL)
        assertEquals(Duration.ofHours(72), AuthPolicy.OWNER_TRANSFER_TTL)
    }

    @Test
    fun `1회용 비밀값은 32바이트 난수`() {
        assertEquals(32, AuthPolicy.SECRET_BYTES)
    }

    @Test
    fun `서명 키는 최소 3072비트`() {
        assertEquals(3072, AuthPolicy.SIGNING_KEY_SIZE)
    }

    @Test
    fun `JWKS 응답은 300초 동안 캐시`() {
        assertEquals(Duration.ofSeconds(300), AuthPolicy.JWKS_CACHE_MAX_AGE)
    }

    @Test
    fun `세션과 verification은 30일, 감사 로그는 1년 보관`() {
        assertEquals(Duration.ofDays(30), AuthPolicy.SESSION_RETENTION)
        assertEquals(Duration.ofDays(30), AuthPolicy.VERIFICATION_RETENTION)
        assertEquals(Duration.ofDays(365), AuthPolicy.AUDIT_RETENTION)
    }

    @Test
    fun `감사 로그는 최대 90일, 기본 7일 범위로 조회`() {
        assertEquals(Duration.ofDays(90), AuthPolicy.AUDIT_QUERY_MAX_RANGE)
        assertEquals(Duration.ofDays(7), AuthPolicy.AUDIT_QUERY_DEFAULT_RANGE)
    }
}
