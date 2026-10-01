package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 감사 이벤트의 action 목록, 기록 단위, detail 규칙 (domain.md §11). */
class AuditEventTest {
    @Test
    fun `AUD-01 action은 명세의 목록과 같음`() {
        assertEquals(
            setOf(
                "LOGIN_SUCCEEDED",
                "LOGIN_FAILED",
                "ACCOUNT_LOCKED",
                "SESSION_REVOKED",
                "PASSWORD_CHANGED",
                "PASSWORD_RESET",
                "PASSWORD_RESET_REQUESTED",
                "EMPLOYEE_INVITED",
                "INVITATION_ACCEPTED",
                "PARTNER_SIGNED_UP",
                "EMAIL_VERIFIED",
                "PROFILE_UPDATED",
                "ACCOUNT_SUSPENDED",
                "ACCOUNT_REACTIVATED",
                "ACCOUNT_DEACTIVATED",
                "ROLE_GRANTED",
                "ROLE_REVOKED",
                "ROLE_DEFINED",
                "ROLE_UPDATED",
                "ROLE_DELETED",
                "AUDIENCE_CREATED",
                "SYSTEM_CLIENT_REGISTERED",
                "CLIENT_SECRET_ROTATED",
                "OWNER_TRANSFER_REQUESTED",
                "OWNER_TRANSFER_CANCELLED",
                "OWNER_TRANSFERRED",
            ),
            AuditAction.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun `대상 종류는 principal, role, audience, 세션`() {
        assertEquals(listOf("PRINCIPAL", "ROLE", "AUDIENCE", "SESSION"), AuditTargetType.entries.map { it.name })
    }

    @Test
    fun `대상 id는 종류에 맞는 id를 문자열로 담음`() {
        val principalId = UUID.fromString("0199a3d0-0000-7000-8000-000000000001")

        assertEquals(AuditTarget(AuditTargetType.PRINCIPAL, "0199a3d0-0000-7000-8000-000000000001"), AuditTarget.principal(principalId))
        assertEquals(AuditTarget(AuditTargetType.SESSION, "0199a3d0-0000-7000-8000-000000000001"), AuditTarget.session(principalId))
        assertEquals(AuditTarget(AuditTargetType.ROLE, "42"), AuditTarget.role(42L))
        assertEquals(AuditTarget(AuditTargetType.AUDIENCE, "5"), AuditTarget.audience(5L))
    }

    @Test
    fun `대상 id가 비어 있거나 컬럼 길이를 넘으면 거부`() {
        assertFailsWith<IllegalArgumentException> { AuditTarget(AuditTargetType.ROLE, " ") }
        assertFailsWith<IllegalArgumentException> { AuditTarget(AuditTargetType.ROLE, "1".repeat(51)) }
    }

    @Test
    fun `AUD-08 계정을 찾지 못한 로그인 실패는 행위자와 대상이 없고 detail에 realm만 남김`() {
        val event = AuditEvent.loginFailedForUnknownAccount(NOW, Realm.PARTNER, "203.0.113.10", "Mozilla/5.0")

        assertEquals(AuditAction.LOGIN_FAILED, event.action)
        assertNull(event.actor)
        assertNull(event.target)
        assertEquals(mapOf("realm" to "partner"), event.detail)
        assertEquals("203.0.113.10", event.ip)
    }

    @Test
    fun `AUD-07 정보 수정은 바뀐 필드 이름을 값으로 남길 수 있음`() {
        val detail = mapOf("fields" to listOf("name", "email", "phone"))

        assertEquals(detail, event(detail).detail)
    }

    @ParameterizedTest
    @ValueSource(strings = ["password", "newPassword", "refreshToken", "token", "clientSecret", "email", "Phone"])
    fun `AUD-07 비밀번호, 토큰, 개인정보를 담는 키는 거부`(key: String) {
        val error = assertFailsWith<IllegalArgumentException> { event(mapOf(key to "value")) }

        assertFalse(error.message.orEmpty().contains("value"), "예외 메시지에 값을 남기지 않음")
    }

    @Test
    fun `AUD-07 중첩된 객체 안의 금지된 키도 거부`() {
        assertFailsWith<IllegalArgumentException> { event(mapOf("changes" to listOf(mapOf("email" to "kim@dozycoffee.com")))) }
    }

    @Test
    fun `JSON으로 저장할 수 없는 detail 값은 거부`() {
        assertFailsWith<IllegalArgumentException> { event(mapOf("at" to NOW)) }
        assertFailsWith<IllegalArgumentException> { event(mapOf("roles" to setOf("wms:manager"))) }
    }

    @Test
    fun `JSON 값과 중첩된 목록·객체는 detail로 받음`() {
        val detail =
            mapOf(
                "roles" to listOf("wms:manager"),
                "revokedSessions" to 2,
                "total" to 3L,
                "ratio" to 0.5,
                "notified" to true,
                "note" to null,
                "previous" to mapOf("via" to "self"),
            )

        assertEquals(detail, event(detail).detail)
    }

    private fun event(detail: Map<String, Any?>) =
        AuditEvent(
            occurredAt = NOW,
            action = AuditAction.PROFILE_UPDATED,
            actor = AuditActor(UUID.fromString("0199a3d0-0000-7000-8000-000000000001"), PrincipalType.EMPLOYEE),
            target = null,
            detail = detail,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
