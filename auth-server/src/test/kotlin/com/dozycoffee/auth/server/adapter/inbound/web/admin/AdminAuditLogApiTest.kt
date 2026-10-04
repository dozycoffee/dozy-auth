package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.application.port.outbound.audit.RecordAuditLogPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.domain.audit.AuditTarget
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * 감사 로그 조회 API (api/admin.md §8, AUD-04, AUD-07).
 *
 * owner는 DB와 토큰에 모두 `auth:owner`를 줍니다. 감사 로그는 [RecordAuditLogPort]로 바로 기록합니다.
 * 응답의 필드 이름과 값 형식은 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class)
@ActiveProfiles("test")
class AdminAuditLogApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @Autowired
    lateinit var recordAuditLog: RecordAuditLogPort

    @Autowired
    lateinit var clock: Clock

    private val jsonMapper = JsonMapper.builder().build()

    @AfterEach
    fun cleanUp() {
        employees.cleanUp()
    }

    @Test
    fun `AUD-04 owner는 기간 안의 감사 로그를 최신순으로 조회함`() {
        val owner = owner()
        val target = UUID.randomUUID()
        record(AuditAction.EMPLOYEE_INVITED, BASE, target = target)
        record(AuditAction.ROLE_GRANTED, BASE.plusSeconds(60), target = target)
        record(AuditAction.PROFILE_UPDATED, BASE.plusSeconds(30), target = target)

        val body = json(list(ownerToken(owner), "from" to BASE.toString(), "to" to BASE.plusSeconds(3600).toString()))

        assertEquals(listOf("ROLE_GRANTED", "PROFILE_UPDATED", "EMPLOYEE_INVITED"), items(body).map { it["action"] })
    }

    @Test
    fun `같은 시각의 감사 로그는 나중에 기록한 것이 먼저`() {
        val owner = owner()
        record(AuditAction.LOGIN_FAILED, BASE)
        record(AuditAction.ACCOUNT_LOCKED, BASE)

        val body = json(list(ownerToken(owner), "from" to BASE.toString(), "to" to BASE.plusSeconds(1).toString()))

        assertEquals(listOf("ACCOUNT_LOCKED", "LOGIN_FAILED"), items(body).map { it["action"] })
    }

    @Test
    fun `AUD-07 항목에는 행위자와 대상의 id와 type, action, detail, ip만 담음`() {
        val owner = owner()
        val actor = employees.create(name = "이감사")
        val target = UUID.randomUUID()
        record(
            AuditAction.ROLE_GRANTED,
            BASE,
            actor = actor.id,
            target = target,
            detail = mapOf("roles" to listOf("wms:inbound_manager")),
            ip = "203.0.113.10",
            userAgent = "Mozilla/5.0",
        )

        val response = list(ownerToken(owner), "from" to BASE.toString(), "to" to BASE.plusSeconds(1).toString())
        val item = items(json(response)).single()

        assertEquals(
            setOf("id", "occurredAt", "actorId", "actorType", "action", "targetType", "targetId", "detail", "ip"),
            item.keys,
        )
        assertEquals(BASE.toString(), item["occurredAt"])
        assertEquals(actor.id.toString(), item["actorId"])
        assertEquals("employee", item["actorType"])
        assertEquals("ROLE_GRANTED", item["action"])
        assertEquals("PRINCIPAL", item["targetType"])
        assertEquals(target.toString(), item["targetId"])
        assertEquals(mapOf("roles" to listOf("wms:inbound_manager")), item["detail"])
        assertEquals("203.0.113.10", item["ip"])
        assertFalse(response.contentAsString.contains(actor.email))
        assertFalse(response.contentAsString.contains(actor.name))
    }

    @Test
    fun `행위자와 대상, detail이 없는 기록은 그 값이 null`() {
        val owner = owner()
        recordEvent(AuditEvent(occurredAt = BASE, action = AuditAction.LOGIN_FAILED, actor = null, target = null))

        val item = items(json(list(ownerToken(owner), "from" to BASE.toString(), "to" to BASE.plusSeconds(1).toString()))).single()

        assertNull(item["actorId"])
        assertNull(item["actorType"])
        assertNull(item["targetType"])
        assertNull(item["targetId"])
        assertNull(item["detail"])
        assertNull(item["ip"])
    }

    // 필터

    @Test
    fun `기간은 from 이상 to 미만`() {
        val owner = owner()
        record(AuditAction.LOGIN_SUCCEEDED, BASE.minusMillis(1))
        record(AuditAction.LOGIN_FAILED, BASE)
        record(AuditAction.ACCOUNT_LOCKED, BASE.plusSeconds(59))
        record(AuditAction.PASSWORD_CHANGED, BASE.plusSeconds(60))

        val body = json(list(ownerToken(owner), "from" to BASE.toString(), "to" to BASE.plusSeconds(60).toString()))

        assertEquals(listOf("ACCOUNT_LOCKED", "LOGIN_FAILED"), items(body).map { it["action"] })
    }

    @Test
    fun `기간을 주지 않으면 지금까지 기본 조회 기간의 기록만 조회함`() {
        val owner = owner()
        val now = clock.instant()
        record(AuditAction.LOGIN_SUCCEEDED, now.minus(AuthPolicy.AUDIT_QUERY_DEFAULT_RANGE).minusSeconds(60))
        record(AuditAction.LOGIN_FAILED, now.minus(AuthPolicy.AUDIT_QUERY_DEFAULT_RANGE).plusSeconds(60))
        record(AuditAction.ACCOUNT_LOCKED, now.minusSeconds(60))

        val body = json(list(ownerToken(owner)))

        assertEquals(listOf("ACCOUNT_LOCKED", "LOGIN_FAILED"), items(body).map { it["action"] })
    }

    @Test
    fun `to만 주면 to 앞으로 기본 조회 기간을 조회함`() {
        val owner = owner()
        val to = BASE.plus(AuthPolicy.AUDIT_QUERY_DEFAULT_RANGE)
        record(AuditAction.LOGIN_SUCCEEDED, BASE.minusSeconds(1))
        record(AuditAction.LOGIN_FAILED, BASE)
        record(AuditAction.ACCOUNT_LOCKED, to.minusSeconds(1))

        val body = json(list(ownerToken(owner), "to" to to.toString()))

        assertEquals(listOf("ACCOUNT_LOCKED", "LOGIN_FAILED"), items(body).map { it["action"] })
    }

    @Test
    fun `from만 주면 지금까지 조회함`() {
        val owner = owner()
        val now = clock.instant()
        val from = now.minusSeconds(3600)
        record(AuditAction.LOGIN_SUCCEEDED, from.minusSeconds(1))
        record(AuditAction.LOGIN_FAILED, now.minusSeconds(60))

        val body = json(list(ownerToken(owner), "from" to from.toString()))

        assertEquals(listOf("LOGIN_FAILED"), items(body).map { it["action"] })
    }

    @Test
    fun `actorId로 행위자의 기록만 조회함`() {
        val owner = owner()
        val actor = UUID.randomUUID()
        record(AuditAction.ROLE_GRANTED, BASE, actor = actor)
        record(AuditAction.ROLE_REVOKED, BASE, actor = UUID.randomUUID())

        val body = json(list(ownerToken(owner), *range(), "actorId" to actor.toString()))

        assertEquals(listOf("ROLE_GRANTED"), items(body).map { it["action"] })
    }

    @Test
    fun `targetType과 targetId로 대상의 기록만 조회함`() {
        val owner = owner()
        val target = UUID.randomUUID()
        record(AuditAction.ACCOUNT_SUSPENDED, BASE, target = target)
        record(AuditAction.ACCOUNT_REACTIVATED, BASE, target = UUID.randomUUID())
        recordEvent(
            AuditEvent(occurredAt = BASE, action = AuditAction.ROLE_DEFINED, actor = null, target = AuditTarget.role(7)),
        )

        val byTarget = json(list(ownerToken(owner), *range(), "targetType" to "PRINCIPAL", "targetId" to target.toString()))
        val byType = json(list(ownerToken(owner), *range(), "targetType" to "ROLE"))

        assertEquals(listOf("ACCOUNT_SUSPENDED"), items(byTarget).map { it["action"] })
        assertEquals(listOf("ROLE_DEFINED"), items(byType).map { it["action"] })
    }

    @Test
    fun `action은 쉼표로 여러 개를 주면 그중 하나인 기록을 조회함`() {
        val owner = owner()
        record(AuditAction.LOGIN_FAILED, BASE)
        record(AuditAction.ACCOUNT_LOCKED, BASE.plusSeconds(1))
        record(AuditAction.LOGIN_SUCCEEDED, BASE.plusSeconds(2))

        val body = json(list(ownerToken(owner), *range(), "action" to "LOGIN_FAILED,ACCOUNT_LOCKED"))

        assertEquals(listOf("ACCOUNT_LOCKED", "LOGIN_FAILED"), items(body).map { it["action"] })
    }

    // 페이지

    @Test
    fun `page와 size로 나눠 조회하고 전체 건수와 페이지 수를 함께 응답함`() {
        val owner = owner()
        (0L until 5L).forEach { record(AuditAction.LOGIN_SUCCEEDED, BASE.plusSeconds(it)) }

        val first = json(list(ownerToken(owner), *range(), "size" to "2"))
        val last = json(list(ownerToken(owner), *range(), "page" to "2", "size" to "2"))

        assertEquals(listOf(BASE.plusSeconds(4).toString(), BASE.plusSeconds(3).toString()), items(first).map { it["occurredAt"] })
        assertEquals(mapOf("number" to 0, "size" to 2, "totalElements" to 5, "totalPages" to 3), first["page"])
        assertEquals(listOf(BASE.toString()), items(last).map { it["occurredAt"] })
        assertEquals(mapOf("number" to 2, "size" to 2, "totalElements" to 5, "totalPages" to 3), last["page"])
    }

    @Test
    fun `page를 주지 않으면 첫 페이지를 기본 크기로 응답함`() {
        val owner = owner()

        val body = json(list(ownerToken(owner), *range()))

        assertEquals(emptyList(), items(body))
        assertEquals(mapOf("number" to 0, "size" to 20, "totalElements" to 0, "totalPages" to 0), body["page"])
    }

    @Test
    fun `size가 최대값을 넘거나 page가 음수면 400 VALIDATION_FAILED`() {
        val token = ownerToken(owner())

        listOf(arrayOf("size" to "101"), arrayOf("size" to "0"), arrayOf("page" to "-1")).forEach { params ->
            assertValidationFailed(list(token, *range(), *params))
        }
    }

    // 기간 검사

    @Test
    fun `AUD-04 조회 기간이 최대 기간과 같으면 조회함`() {
        val owner = owner()
        val to = BASE.plus(AuthPolicy.AUDIT_QUERY_MAX_RANGE)
        record(AuditAction.LOGIN_FAILED, BASE)

        val response = list(ownerToken(owner), "from" to BASE.toString(), "to" to to.toString())

        assertEquals(200, response.status)
        assertEquals(listOf("LOGIN_FAILED"), items(json(response)).map { it["action"] })
    }

    @Test
    fun `AUD-04 조회 기간이 최대 기간을 넘으면 400 VALIDATION_FAILED`() {
        val to = BASE.plus(AuthPolicy.AUDIT_QUERY_MAX_RANGE).plusSeconds(1)

        assertValidationFailed(list(ownerToken(owner()), "from" to BASE.toString(), "to" to to.toString()))
    }

    @Test
    fun `from만 줘서 지금까지가 최대 기간을 넘으면 400 VALIDATION_FAILED`() {
        val from = clock.instant().minus(AuthPolicy.AUDIT_QUERY_MAX_RANGE).minusSeconds(3600)

        assertValidationFailed(list(ownerToken(owner()), "from" to from.toString()))
    }

    @Test
    fun `from이 to보다 앞이 아니면 400 VALIDATION_FAILED`() {
        val token = ownerToken(owner())

        assertValidationFailed(list(token, "from" to BASE.toString(), "to" to BASE.toString()))
        assertValidationFailed(list(token, "from" to BASE.plusSeconds(1).toString(), "to" to BASE.toString()))
    }

    @Test
    fun `기간이나 actorId 형식이 틀리면 400 VALIDATION_FAILED`() {
        val token = ownerToken(owner())

        assertValidationFailed(list(token, "from" to "2026-13-01", "to" to BASE.toString()))
        assertValidationFailed(list(token, "from" to BASE.toString(), "to" to "어제"))
        assertValidationFailed(list(token, *range(), "actorId" to "not-a-uuid"))
    }

    @Test
    fun `모르는 action이나 targetType이면 400 VALIDATION_FAILED`() {
        val token = ownerToken(owner())

        assertValidationFailed(list(token, *range(), "action" to "LOGIN_FAILED,NO_SUCH_ACTION"))
        assertValidationFailed(list(token, *range(), "action" to "login_failed"))
        assertValidationFailed(list(token, *range(), "targetType" to "CLIENT"))
    }

    // 권한

    @Test
    fun `AUD-04 admin은 403 FORBIDDEN`() {
        val admin = employees.create().also { employees.makeAdmin(it) }

        val response = list(tokens.issue(admin.key, roles = listOf("auth:admin")), *range())

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
    }

    @Test
    fun `AUD-04 토큰에 owner role이 있어도 DB에서 owner가 아니면 403 FORBIDDEN`() {
        val former = employees.create().also { employees.makeAdmin(it) }

        val response = list(tokens.issue(former.key, roles = listOf("auth:owner")), *range())

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
    }

    @Test
    fun `DB 기준 owner가 아니면 기간이 틀려도 403 FORBIDDEN`() {
        val former = employees.create()

        val response = list(tokens.issue(former.key, roles = listOf("auth:owner")), "from" to BASE.toString(), "to" to BASE.toString())

        assertEquals(403, response.status)
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        val response = list(null, *range())

        assertEquals(401, response.status)
        assertEquals("UNAUTHENTICATED", json(response)["code"])
    }

    private fun owner(): CreatedEmployee = employees.create().also { employees.makeOwner(it) }

    private fun ownerToken(owner: CreatedEmployee): String = tokens.issue(owner.key, roles = listOf("auth:owner"))

    private fun range(): Array<Pair<String, String>> = arrayOf("from" to BASE.toString(), "to" to BASE.plusSeconds(3600).toString())

    private fun record(
        action: AuditAction,
        occurredAt: Instant,
        actor: UUID? = UUID.randomUUID(),
        target: UUID? = UUID.randomUUID(),
        detail: Map<String, Any?> = emptyMap(),
        ip: String? = null,
        userAgent: String? = null,
    ) {
        recordEvent(
            AuditEvent(
                occurredAt = occurredAt,
                action = action,
                actor = actor?.let { AuditActor(it, PrincipalType.EMPLOYEE) },
                target = target?.let(AuditTarget::principal),
                detail = detail,
                ip = ip,
                userAgent = userAgent,
            ),
        )
    }

    private fun recordEvent(event: AuditEvent) {
        employees.inTransaction { recordAuditLog.record(event) }
    }

    private fun list(
        token: String?,
        vararg params: Pair<String, String>,
    ): MockHttpServletResponse =
        mockMvc
            .get("/admin/audit-logs") {
                token?.let { header("Authorization", "Bearer $it") }
                params.forEach { (name, value) -> param(name, value) }
            }.andReturn()
            .response

    private fun assertValidationFailed(response: MockHttpServletResponse) {
        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(body: Map<String, Any?>): List<Map<String, Any?>> = body["items"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        /** 명시한 기간으로 조회하는 테스트의 기준 시각. 지금과 떨어진 과거라 기본 기간 조회와 겹치지 않습니다. */
        val BASE: Instant = Instant.parse("2026-03-01T00:00:00Z")
    }
}
