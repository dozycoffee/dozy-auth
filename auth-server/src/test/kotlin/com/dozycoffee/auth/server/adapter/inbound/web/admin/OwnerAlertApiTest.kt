package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AudienceTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerNotificationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferCompletedMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferRequestMail
import com.dozycoffee.auth.server.application.service.admin.OwnerAlerts
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.domain.audit.AuditActor
import com.dozycoffee.auth.server.domain.audit.AuditEvent
import com.dozycoffee.auth.server.support.RecordingMailConfig
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import com.dozycoffee.auth.server.support.counted
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * owner 즉시 알림 (AUD-01, AUD-02, AUD-03, AUD-08). 관리 API를 실제로 호출해 커밋 후 발송된 메일을 [RecordingMailSender]로 확인합니다.
 * 다른 관리 API 테스트와 같은 설정을 써서 스프링 컨텍스트를 새로 만들지 않습니다.
 *
 * owner는 DB 전체에서 한 명이므로(GOV-10) 테스트마다 만들고 [TestEmployees.cleanUp]으로 지웁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class, RecordingMailConfig::class)
@ActiveProfiles("test")
class OwnerAlertApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @Autowired
    lateinit var mails: RecordingMailSender

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var loadRole: LoadRolePort

    @Autowired
    lateinit var ownerAlerts: OwnerAlerts

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    private val jsonMapper = JsonMapper.builder().build()

    private val audiences = mutableListOf<Long>()
    private val clients = mutableListOf<UUID>()

    @BeforeEach
    fun setUp() = mails.sent.clear()

    @AfterEach
    fun cleanUp() {
        mails.failing = false
        employees.inTransaction {
            if (clients.isNotEmpty()) SystemClientTable.deleteWhere { SystemClientTable.principalId inList clients }
            if (audiences.isNotEmpty()) AudienceTable.deleteWhere { AudienceTable.id inList audiences }
        }
        clients.clear()
        audiences.clear()
        employees.cleanUp()
    }

    // 즉시 알림 대상

    @Test
    fun `AUD-02 owner가 admin을 임명·해임하면 각각 owner 본인에게 알림 한 통`() {
        val owner = owner()
        val target = employees.create()
        val before = clock.instant()

        assertEquals(204, grant(ownerToken(owner), target.id, listOf("auth:admin")).status)
        assertAlerted(owner, AuditAction.ROLE_GRANTED, before)

        mails.sent.clear()
        val revoked = before()
        assertEquals(204, revoke(ownerToken(owner), target.id, "auth:admin").status)
        assertAlerted(owner, AuditAction.ROLE_REVOKED, revoked)
    }

    @Test
    fun `AUD-01 AUD-08 auth role을 포함한 직원 초대는 초대 메일과 EMPLOYEE_INVITED 알림 한 통`() {
        val owner = owner()
        val before = clock.instant()

        val response = invite(ownerToken(owner), listOf("auth:admin"))

        assertEquals(201, response.status)
        assertEquals(1, mails.sent.filterIsInstance<EmployeeInvitationMail>().size)
        assertAlerted(owner, AuditAction.EMPLOYEE_INVITED, before, total = 2)
    }

    @Test
    fun `AUD-01 role 정의 삭제는 일괄 회수가 있어도 ROLE_DELETED 알림 한 통이고 admin이 해도 owner가 받음`() {
        val owner = owner()
        val admin = admin()
        // 없는 role은 create가 정의합니다
        val code = employees.newRoleCode("wms")
        employees.create(roles = listOf(code))
        val roleId = employees.inTransaction { checkNotNull(loadRole.findRoleByCode(code)).id }
        val before = clock.instant()

        val response =
            mockMvc
                .delete("/admin/roles/$roleId?revokeAll=true") { header("Authorization", "Bearer ${adminToken(admin)}") }
                .andReturn()
                .response

        assertEquals(204, response.status)
        assertAlerted(owner, AuditAction.ROLE_DELETED, before)
    }

    @Test
    fun `AUD-01 audience 추가는 AUDIENCE_CREATED 알림 한 통`() {
        val owner = owner()
        val before = clock.instant()

        val response = post("/admin/audiences", ownerToken(owner), mapOf("code" to newCode(), "name" to "주문"))

        assertEquals(201, response.status)
        audiences += (json(response)["id"] as Number).toLong()
        assertAlerted(owner, AuditAction.AUDIENCE_CREATED, before)
    }

    @Test
    fun `AUD-01 system client 등록과 secret 재발급은 각각 알림 한 통이고 role을 함께 부여해도 등록 알림은 한 통`() {
        val owner = owner()
        val admin = admin()
        val code = employees.newRoleCode("wms").also(employees::defineRole)
        val before = clock.instant()

        val registered =
            post(
                "/admin/system-clients",
                adminToken(admin),
                mapOf(
                    "clientId" to "svc-${newCode()}",
                    "name" to "WMS",
                    "roles" to listOf(code.value),
                ),
            )

        assertEquals(201, registered.status)
        val principalId = UUID.fromString(json(registered)["principalId"] as String).also(::trackClient)
        assertAlerted(owner, AuditAction.SYSTEM_CLIENT_REGISTERED, before)

        mails.sent.clear()
        val rotated = before()
        assertEquals(200, post("/admin/system-clients/$principalId/secret", adminToken(admin), null).status)
        assertAlerted(owner, AuditAction.CLIENT_SECRET_ROTATED, rotated)
    }

    @Test
    fun `AUD-01 AUD-03 owner 양도 요청·취소는 owner 본인이, 완료는 새 owner가 알림 한 통씩 받고 이전 owner는 완료 메일을 받음`() {
        val owner = owner()
        val target = employees.create()

        val requested = before()
        assertEquals(202, post("/admin/owner/transfer", ownerToken(owner), mapOf("targetPrincipalId" to target.id.toString())).status)
        assertEquals(1, mails.sent.filterIsInstance<OwnerTransferRequestMail>().size)
        assertAlerted(owner, AuditAction.OWNER_TRANSFER_REQUESTED, requested, total = 2)

        mails.sent.clear()
        val cancelled = before()
        val cancel =
            mockMvc
                .delete("/admin/owner/transfer") { header("Authorization", "Bearer ${ownerToken(owner)}") }
                .andReturn()
                .response
        assertEquals(204, cancel.status)
        assertAlerted(owner, AuditAction.OWNER_TRANSFER_CANCELLED, cancelled)

        mails.sent.clear()
        post("/admin/owner/transfer", ownerToken(owner), mapOf("targetPrincipalId" to target.id.toString()))
        val transferToken =
            mails.sent
                .filterIsInstance<OwnerTransferRequestMail>()
                .single()
                .token.value
        mails.sent.clear()
        val accepted = before()
        assertEquals(204, post("/admin/owner/transfer/accept", tokens.issue(target.key), mapOf("token" to transferToken)).status)
        assertEquals(
            Email(owner.email),
            mails.sent
                .filterIsInstance<OwnerTransferCompletedMail>()
                .single()
                .to,
        )
        assertAlerted(target, AuditAction.OWNER_TRANSFERRED, accepted, total = 2)
    }

    // 즉시 알림 대상이 아님

    @Test
    fun `AUD-03 일반 role 부여·회수, auth role 없는 초대, 정지와 재설정 메일 발송은 알리지 않음`() {
        val owner = owner()
        val admin = admin()
        val target = employees.create()
        val code = employees.newRoleCode("wms").also(employees::defineRole)

        assertEquals(204, grant(adminToken(admin), target.id, listOf(code.value)).status)
        assertEquals(204, revoke(adminToken(admin), target.id, code.value).status)
        assertEquals(201, invite(ownerToken(owner), listOf(code.value)).status)
        assertEquals(204, post("/admin/principals/${target.id}/suspend", adminToken(admin), mapOf("reason" to "퇴사 예정")).status)
        val other = employees.create()
        assertEquals(202, post("/admin/principals/${other.id}/password-reset", adminToken(admin), null).status)

        assertTrue(mails.sent.filterIsInstance<OwnerNotificationMail>().isEmpty())
    }

    @Test
    fun `AUD-03 업무가 롤백되면 알림을 보내지 않음`() {
        owner()
        val event = AuditEvent(clock.instant(), AuditAction.AUDIENCE_CREATED, AuditActor(UUID.randomUUID(), PrincipalType.EMPLOYEE), null)

        assertFailsWith<IllegalStateException> {
            employees.inTransaction {
                ownerAlerts.notifyIfRequired(event)
                error("업무 실패")
            }
        }

        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `AUD-03 ACTIVE owner가 없으면 알림 없이 요청은 성공함`() {
        val admin = admin()

        val response = post("/admin/system-clients", adminToken(admin), mapOf("clientId" to "svc-${newCode()}", "name" to "WMS"))

        assertEquals(201, response.status)
        trackClient(UUID.fromString(json(response)["principalId"] as String))
        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `AUD-03 알림 발송에 실패해도 다시 보내지 않고 요청은 성공하며 실패를 지표로 셈`() {
        val owner = owner()
        val target = employees.create()
        val failedBefore = meterRegistry.counted("dozy.auth.mail.failed", "kind", "owner_notification")
        mails.failing = true

        val response = grant(ownerToken(owner), target.id, listOf("auth:admin"))

        assertEquals(204, response.status)
        assertTrue(mails.sent.isEmpty())
        assertEquals(failedBefore + 1, meterRegistry.counted("dozy.auth.mail.failed", "kind", "owner_notification"))
    }

    private fun assertAlerted(
        owner: CreatedEmployee,
        action: AuditAction,
        notBefore: Instant,
        total: Int = 1,
    ) {
        assertEquals(total, mails.sent.size, "보낸 메일: ${mails.sent.map { it::class.simpleName }}")
        val alert = mails.sent.filterIsInstance<OwnerNotificationMail>().single()
        assertEquals(Email(owner.email), alert.to)
        assertEquals(action, alert.action)
        assertTrue(alert.occurredAt in notBefore..clock.instant(), "발생 시각 ${alert.occurredAt}")
    }

    private fun before(): Instant = clock.instant()

    private fun owner(): CreatedEmployee = employees.create().also(employees::makeOwner)

    private fun admin(): CreatedEmployee = employees.create().also(employees::makeAdmin)

    private fun ownerToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:owner"))

    private fun adminToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:admin"))

    private fun newCode(): String = "t${UUID.randomUUID().toString().replace("-", "").take(20)}"

    private fun trackClient(principalId: UUID) {
        clients += principalId
        employees.track(principalId)
    }

    private fun grant(
        token: String,
        principalId: UUID,
        roles: List<String>,
    ): MockHttpServletResponse = post("/admin/principals/$principalId/roles", token, mapOf("roles" to roles))

    private fun revoke(
        token: String,
        principalId: UUID,
        role: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/admin/principals/$principalId/roles/$role") { header("Authorization", "Bearer $token") }
            .andReturn()
            .response

    private fun invite(
        token: String,
        roles: List<String>,
    ): MockHttpServletResponse {
        val email = "invitee-${UUID.randomUUID()}@dozycoffee.test"
        return post("/admin/employees", token, mapOf("email" to email, "name" to "김초대", "roles" to roles)).also {
            if (it.status == 201) employees.track(UUID.fromString(json(it)["principalId"] as String))
        }
    }

    private fun post(
        path: String,
        token: String,
        body: Map<String, Any?>?,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                header("Authorization", "Bearer $token")
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(it)
                }
            }.andReturn()
            .response

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>
}
