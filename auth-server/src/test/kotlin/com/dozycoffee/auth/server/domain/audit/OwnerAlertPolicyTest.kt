package com.dozycoffee.auth.server.domain.audit

import com.dozycoffee.auth.core.PrincipalType
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** owner 즉시 알림 대상 판단 (domain.md AUD-01 표의 "owner 알림", AUD-02, AUD-08). */
class OwnerAlertPolicyTest {
    @Test
    fun `AUD-01 즉시 알림 대상 action은 명세의 표와 같음`() {
        val immediate =
            AuditAction.entries
                .filter { OwnerAlertPolicy.requiresImmediateAlert(event(it)) }
                .map { it.name }
                .toSet()

        assertEquals(
            setOf(
                "ROLE_DELETED",
                "AUDIENCE_CREATED",
                "SYSTEM_CLIENT_REGISTERED",
                "CLIENT_SECRET_ROTATED",
                "OWNER_TRANSFER_REQUESTED",
                "OWNER_TRANSFER_CANCELLED",
                "OWNER_TRANSFERRED",
            ),
            immediate,
        )
    }

    @Test
    fun `AUD-02 auth audience role을 부여·회수하면 즉시 알림이고 일반 role은 아님`() {
        listOf(AuditAction.ROLE_GRANTED, AuditAction.ROLE_REVOKED).forEach { action ->
            assertTrue(OwnerAlertPolicy.requiresImmediateAlert(event(action, listOf("wms:inbound_manager", "auth:admin"))), "$action")
            assertFalse(OwnerAlertPolicy.requiresImmediateAlert(event(action, listOf("wms:inbound_manager"))), "$action")
        }
    }

    @Test
    fun `AUD-08 직원 초대는 함께 남긴 ROLE_GRANTED로 판단함`() {
        val invited = event(AuditAction.EMPLOYEE_INVITED)

        assertTrue(OwnerAlertPolicy.requiresImmediateAlert(invited, listOf(event(AuditAction.ROLE_GRANTED, listOf("auth:admin")))))
        assertFalse(OwnerAlertPolicy.requiresImmediateAlert(invited, listOf(event(AuditAction.ROLE_GRANTED, listOf("wms:picker")))))
        assertFalse(OwnerAlertPolicy.requiresImmediateAlert(invited, emptyList()))
    }

    private fun event(
        action: AuditAction,
        roles: List<String>? = null,
    ): AuditEvent =
        AuditEvent(
            occurredAt = Instant.parse("2026-10-05T00:00:00Z"),
            action = action,
            actor = AuditActor(UUID.randomUUID(), PrincipalType.EMPLOYEE),
            target = null,
            detail = roles?.let { mapOf("roles" to it) } ?: emptyMap(),
        )
}
