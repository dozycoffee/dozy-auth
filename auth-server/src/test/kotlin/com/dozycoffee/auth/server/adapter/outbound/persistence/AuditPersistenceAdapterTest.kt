package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.application.port.outbound.audit.AuditLogQuery
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.domain.audit.AuditTargetType
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 감사 로그의 저장과 조회 (docs/data-model.md §3.11, AUD-04·06·08). */
@PersistenceAdapterTest
class AuditPersistenceAdapterTest {
    private val adapter = AuditPersistenceAdapter()

    @Test
    fun `기록한 감사 로그의 모든 값을 다시 읽음`() {
        val event =
            AuditEvent(
                occurredAt = NOW,
                action = AuditAction.ROLE_GRANTED,
                actor = AuditActor(OWNER_ID, PrincipalType.EMPLOYEE),
                target = AuditTarget.principal(EMPLOYEE_ID),
                detail = mapOf("roles" to listOf("wms:inbound_manager", "store:viewer")),
                ip = "203.0.113.10",
                userAgent = "Mozilla/5.0",
            )

        adapter.record(event)

        val entry = adapter.findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(1))).entries.single()
        assertEquals(event, entry.event)
        val row = AuditLogTable.selectAll().single()
        assertEquals(entry.id, row[AuditLogTable.id])
        assertEquals("EMPLOYEE", row[AuditLogTable.actorType])
        assertEquals("ROLE_GRANTED", row[AuditLogTable.action])
        assertEquals("PRINCIPAL", row[AuditLogTable.targetType])
        assertEquals(EMPLOYEE_ID.toString(), row[AuditLogTable.targetId])
    }

    @Test
    fun `AUD-06 principal 테이블에 없는 행위자와 대상도 기록함`() {
        adapter.record(event(AuditAction.ACCOUNT_DEACTIVATED, detail = mapOf("via" to "self")))

        assertEquals(1L, adapter.findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(1))).totalElements)
    }

    @Test
    fun `role과 audience 대상은 bigint id를 문자열로 남김`() {
        adapter.record(event(AuditAction.ROLE_DEFINED, target = AuditTarget.role(42L)))
        adapter.record(event(AuditAction.AUDIENCE_CREATED, target = AuditTarget.audience(5L)))

        val targets = AuditLogTable.selectAll().map { it[AuditLogTable.targetType] to it[AuditLogTable.targetId] }.toSet()

        assertEquals(setOf("ROLE" to "42", "AUDIENCE" to "5"), targets)
    }

    @Test
    fun `AUD-08 계정을 찾지 못한 로그인 실패는 행위자와 대상 없이 realm만 남김`() {
        adapter.record(AuditEvent.loginFailedForUnknownAccount(NOW, Realm.INTERNAL, "203.0.113.10", "Mozilla/5.0"))

        val row = AuditLogTable.selectAll().single()
        assertEquals("LOGIN_FAILED", row[AuditLogTable.action])
        assertNull(row[AuditLogTable.actorId])
        assertNull(row[AuditLogTable.actorType])
        assertNull(row[AuditLogTable.targetType])
        assertNull(row[AuditLogTable.targetId])
        assertEquals(mapOf("realm" to "internal"), row[AuditLogTable.detail])
    }

    @Test
    fun `시스템 작업은 행위자 없이 기록하고 detail이 없으면 NULL로 남김`() {
        adapter.record(event(AuditAction.EMPLOYEE_INVITED, actor = null, ip = null, userAgent = null))

        val row = AuditLogTable.selectAll().single()
        assertNull(row[AuditLogTable.actorId])
        assertNull(row[AuditLogTable.detail])
        assertNull(row[AuditLogTable.ip])
        assertNull(row[AuditLogTable.userAgent])
        val read =
            adapter
                .findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(1)))
                .entries
                .single()
                .event
        assertNull(read.actor)
        assertEquals(emptyMap(), read.detail)
    }

    @Test
    fun `중첩된 detail을 그대로 돌려줌`() {
        val detail =
            mapOf(
                "roles" to listOf("wms:inbound_manager"),
                "revokedSessions" to 3,
                "via" to "invitation_cancelled",
                "previous" to mapOf("owner" to OWNER_ID.toString(), "notified" to true, "note" to null),
            )

        adapter.record(event(AuditAction.OWNER_TRANSFERRED, detail = detail))

        assertEquals(
            detail,
            adapter
                .findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(1)))
                .entries
                .single()
                .event.detail,
        )
    }

    @Test
    fun `user agent가 컬럼 길이를 넘으면 잘라서 저장함`() {
        adapter.record(event(AuditAction.LOGIN_SUCCEEDED, userAgent = "a".repeat(300)))

        assertEquals("a".repeat(255), AuditLogTable.selectAll().single()[AuditLogTable.userAgent])
    }

    @Test
    fun `AUD-04 기간 안의 감사 로그를 최신순으로 돌려줌`() {
        adapter.record(event(AuditAction.LOGIN_SUCCEEDED, occurredAt = NOW))
        adapter.record(event(AuditAction.PASSWORD_CHANGED, occurredAt = NOW.plusSeconds(120)))
        adapter.record(event(AuditAction.PROFILE_UPDATED, occurredAt = NOW.plusSeconds(60)))
        adapter.record(event(AuditAction.SESSION_REVOKED, occurredAt = NOW.plusSeconds(60)))

        val page = adapter.findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(180)))

        assertEquals(
            listOf(AuditAction.PASSWORD_CHANGED, AuditAction.SESSION_REVOKED, AuditAction.PROFILE_UPDATED, AuditAction.LOGIN_SUCCEEDED),
            page.entries.map { it.event.action },
        )
        assertEquals(4L, page.totalElements)
    }

    @Test
    fun `기간은 시작 시각을 포함하고 끝 시각을 제외함`() {
        adapter.record(event(AuditAction.LOGIN_FAILED, occurredAt = NOW.minusMillis(1)))
        adapter.record(event(AuditAction.LOGIN_SUCCEEDED, occurredAt = NOW))
        adapter.record(event(AuditAction.PASSWORD_CHANGED, occurredAt = NOW.plusSeconds(59)))
        adapter.record(event(AuditAction.SESSION_REVOKED, occurredAt = NOW.plusSeconds(60)))

        val page = adapter.findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(60)))

        assertEquals(listOf(AuditAction.PASSWORD_CHANGED, AuditAction.LOGIN_SUCCEEDED), page.entries.map { it.event.action })
    }

    @Test
    fun `행위자로 거름`() {
        val other = UUID.fromString("0199a3d0-0000-7000-8000-000000000003")
        adapter.record(event(AuditAction.ROLE_GRANTED, actor = AuditActor(OWNER_ID, PrincipalType.EMPLOYEE)))
        adapter.record(event(AuditAction.ROLE_REVOKED, actor = AuditActor(other, PrincipalType.EMPLOYEE)))
        adapter.record(event(AuditAction.EMPLOYEE_INVITED, actor = null))

        val page = adapter.findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(1), actorId = OWNER_ID))

        assertEquals(listOf(AuditAction.ROLE_GRANTED), page.entries.map { it.event.action })
    }

    @Test
    fun `대상 종류와 id로 거름`() {
        adapter.record(event(AuditAction.ACCOUNT_SUSPENDED, target = AuditTarget.principal(EMPLOYEE_ID)))
        adapter.record(event(AuditAction.ACCOUNT_REACTIVATED, target = AuditTarget.principal(OWNER_ID)))
        adapter.record(event(AuditAction.ROLE_UPDATED, target = AuditTarget.role(7L)))

        val byType = adapter.findAuditLogs(AuditLogQuery(from = NOW, to = NOW.plusSeconds(1), targetType = AuditTargetType.PRINCIPAL))
        val byTarget =
            adapter.findAuditLogs(
                AuditLogQuery(
                    from = NOW,
                    to = NOW.plusSeconds(1),
                    targetType = AuditTargetType.PRINCIPAL,
                    targetId = EMPLOYEE_ID.toString(),
                ),
            )

        assertEquals(setOf(AuditAction.ACCOUNT_SUSPENDED, AuditAction.ACCOUNT_REACTIVATED), byType.entries.map { it.event.action }.toSet())
        assertEquals(listOf(AuditAction.ACCOUNT_SUSPENDED), byTarget.entries.map { it.event.action })
    }

    @Test
    fun `여러 action 중 하나인 것만 거름`() {
        adapter.record(event(AuditAction.LOGIN_SUCCEEDED))
        adapter.record(event(AuditAction.LOGIN_FAILED))
        adapter.record(event(AuditAction.ACCOUNT_LOCKED))

        val page =
            adapter.findAuditLogs(
                AuditLogQuery(from = NOW, to = NOW.plusSeconds(1), actions = setOf(AuditAction.LOGIN_FAILED, AuditAction.ACCOUNT_LOCKED)),
            )

        assertEquals(setOf(AuditAction.LOGIN_FAILED, AuditAction.ACCOUNT_LOCKED), page.entries.map { it.event.action }.toSet())
        assertEquals(2L, page.totalElements)
    }

    @Test
    fun `페이지 크기만큼 나눠 돌려주고 전체 건수를 함께 돌려줌`() {
        (0L until 5L).forEach { adapter.record(event(AuditAction.LOGIN_SUCCEEDED, occurredAt = NOW.plusSeconds(it))) }
        val query = AuditLogQuery(from = NOW, to = NOW.plusSeconds(60), size = 2)

        val first = adapter.findAuditLogs(query)
        val last = adapter.findAuditLogs(query.copy(page = 2))
        val beyond = adapter.findAuditLogs(query.copy(page = 3))

        assertEquals(listOf(NOW.plusSeconds(4), NOW.plusSeconds(3)), first.entries.map { it.event.occurredAt })
        assertEquals(listOf(NOW), last.entries.map { it.event.occurredAt })
        assertEquals(emptyList(), beyond.entries)
        assertEquals(5L, first.totalElements)
        assertEquals(5L, beyond.totalElements)
    }

    private fun event(
        action: AuditAction,
        occurredAt: Instant = NOW,
        actor: AuditActor? = AuditActor(OWNER_ID, PrincipalType.EMPLOYEE),
        target: AuditTarget? = AuditTarget.principal(EMPLOYEE_ID),
        detail: Map<String, Any?> = emptyMap(),
        ip: String? = "203.0.113.10",
        userAgent: String? = "Mozilla/5.0",
    ) = AuditEvent(occurredAt, action, actor, target, detail, ip, userAgent)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        val OWNER_ID: UUID = UUID.fromString("0199a3d0-0000-7000-8000-000000000001")
        val EMPLOYEE_ID: UUID = UUID.fromString("0199a3d0-0000-7000-8000-000000000002")
    }
}
