package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditLogTable
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.RecordingMailConfig
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import com.nimbusds.jwt.SignedJWT
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
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
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 직원 초대 (api/admin.md 직원 초대, VER-01, GOV-05~08, GOV-14, GOV-15, AUD-08). 실제 DB에 커밋하며 초대 → 수락 → 로그인까지 확인합니다.
 *
 * 응답 필드 이름, 에러 code, 감사 action과 detail은 명세의 문자열 그대로 기대값으로 씁니다. 관리자의 관리 등급은 DB의 role로 정하므로
 * 관리자도 DB에 role을 부여하고, 토큰에도 같은 role을 담습니다. API가 만든 직원은 [TestEmployees.track]으로 정리 대상에 넣습니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class, RecordingMailConfig::class)
@ActiveProfiles("test")
class AdminEmployeeInviteApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @Autowired
    lateinit var mails: RecordingMailSender

    @Autowired
    lateinit var loadEmployee: LoadEmployeePort

    @Autowired
    lateinit var loadPrincipalRoles: LoadPrincipalRolesPort

    @Autowired
    lateinit var clock: Clock

    private val jsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() = mails.sent.clear()

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    @Test
    fun `role 없이 초대하면 PENDING 직원을 만들고 초대 메일을 보내며 수락하면 로그인할 수 있음`() {
        val admin = admin()
        val email = newEmail()

        val response = invite(adminToken(admin), mapOf("email" to email, "name" to "김초대", "phone" to "010-1234-5678"))

        assertEquals(201, response.status)
        val body = json(response)
        assertEquals(setOf("principalId", "status", "invitationExpiresAt"), body.keys)
        assertEquals("PENDING", body["status"])
        val id = track(body)
        val expiresAt = Instant.parse(body["invitationExpiresAt"] as String)
        assertTrue(!expiresAt.isBefore(clock.instant().plus(AuthPolicy.INVITATION_TTL).minusSeconds(60)))

        val employee = assertNotNull(employees.inTransaction { loadEmployee.findEmployeeById(id) })
        assertEquals(AccountStatus.PENDING, employee.account.status)
        assertEquals(email, employee.profile.email.value)
        assertEquals("김초대", employee.profile.name)
        assertEquals("010-1234-5678", employee.profile.phone)

        val mail = mails.sent.single() as EmployeeInvitationMail
        assertEquals(Email(email), mail.to)
        assertEquals("김초대", mail.name)
        assertEquals(expiresAt, mail.expiresAt)
        assertEquals("김초대", json(verifyInvitation(mail.token.value))["name"])

        assertEquals(204, acceptInvitation(mail.token.value).status)
        val login = login(email)
        assertEquals(200, login.status)
        val claims = SignedJWT.parse(json(login)["accessToken"] as String).jwtClaimsSet
        assertEquals("employee:$id", claims.subject)
        assertTrue(claims.audience.isNullOrEmpty())
    }

    @Test
    fun `GOV-07 role을 지정해 초대하면 PENDING 직원에게 부여하고 수락 후 로그인 토큰에 담김`() {
        val admin = admin()
        val wms = employees.newRoleCode("wms")
        val catalog = employees.newRoleCode("catalog")
        employees.create(roles = listOf(wms, catalog)) // role 정의를 만들기 위한 다른 직원
        val email = newEmail()

        val response = invite(adminToken(admin), mapOf("email" to email, "name" to "김초대", "roles" to listOf(wms.value, catalog.value)))

        assertEquals(201, response.status)
        val id = track(json(response))
        val mail = mails.sent.single() as EmployeeInvitationMail
        assertEquals(204, acceptInvitation(mail.token.value).status)
        val claims = SignedJWT.parse(json(login(email))["accessToken"] as String).jwtClaimsSet
        assertEquals("employee:$id", claims.subject)
        assertEquals(setOf("wms", "catalog"), claims.audience.toSet())
        assertEquals(setOf(wms.value, catalog.value), claims.getStringListClaim("roles").toSet())
    }

    @Test
    fun `AUD-08 초대는 EMPLOYEE_INVITED와 지정한 role의 ROLE_GRANTED를 남김`() {
        val admin = admin()
        val wms = employees.newRoleCode("wms")
        val catalog = employees.newRoleCode("catalog")
        employees.create(roles = listOf(wms, catalog))

        val response =
            invite(
                adminToken(admin),
                mapOf("email" to newEmail(), "name" to "김초대", "roles" to listOf(wms.value, catalog.value, wms.value)),
                userAgent = "DozyConsole/1.0",
            )

        val id = track(json(response))
        val audits = audits(id)
        assertEquals(listOf("EMPLOYEE_INVITED", "ROLE_GRANTED"), audits.map { it.action })
        assertTrue(audits.all { it.actorId == admin.id && it.userAgent == "DozyConsole/1.0" })
        assertEquals(emptyMap(), audits[0].detail.orEmpty())
        assertEquals(mapOf("roles" to listOf(wms.value, catalog.value).sorted()), audits[1].detail)
    }

    @Test
    fun `AUD-08 role 없이 초대하면 EMPLOYEE_INVITED만 남김`() {
        val admin = admin()

        val id = track(json(invite(adminToken(admin), mapOf("email" to newEmail(), "name" to "김초대", "roles" to emptyList<String>()))))

        assertEquals(listOf("EMPLOYEE_INVITED"), audits(id).map { it.action })
    }

    @Test
    fun `GOV-05 admin이 auth admin을 지정하면 FORBIDDEN이고 아무것도 만들지 않음`() {
        val admin = admin()
        val email = newEmail()

        val response = invite(adminToken(admin), mapOf("email" to email, "name" to "김초대", "roles" to listOf("auth:admin")))

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
        assertNothingCreated(email)
    }

    @Test
    fun `GOV-05 owner는 auth admin을 지정해 초대할 수 있음`() {
        val owner = owner()
        val email = newEmail()

        val response = invite(ownerToken(owner), mapOf("email" to email, "name" to "김초대", "roles" to listOf("auth:admin")))

        assertEquals(201, response.status)
        val id = track(json(response))
        assertEquals(listOf("auth:admin"), roleCodes(id))
    }

    @Test
    fun `GOV-05 owner라도 auth owner를 지정하면 FORBIDDEN이고 아무것도 만들지 않음`() {
        val owner = owner()
        val email = newEmail()

        val response = invite(ownerToken(owner), mapOf("email" to email, "name" to "김초대", "roles" to listOf("auth:owner")))

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
        assertNothingCreated(email)
    }

    @Test
    fun `GOV-08 없는 role이 하나라도 있으면 NOT_FOUND이고 아무것도 만들지 않음`() {
        val admin = admin()
        val existing = employees.newRoleCode("wms")
        employees.create(roles = listOf(existing))
        val email = newEmail()

        val response =
            invite(
                adminToken(admin),
                mapOf("email" to email, "name" to "김초대", "roles" to listOf(existing.value, employees.newRoleCode("wms").value)),
            )

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", json(response)["code"])
        assertNothingCreated(email)
    }

    @Test
    fun `GOV-15 없는 role은 GOV-05보다 먼저 NOT_FOUND`() {
        val admin = admin()

        val response =
            invite(
                adminToken(admin),
                mapOf("email" to newEmail(), "name" to "김초대", "roles" to listOf("auth:admin", employees.newRoleCode("wms").value)),
            )

        assertEquals("NOT_FOUND", json(response)["code"])
    }

    @Test
    fun `대소문자만 다른 이메일의 직원이 있으면 409 DUPLICATE_EMAIL이고 메일을 보내지 않음`() {
        val admin = admin()
        val existing = employees.create()

        val response = invite(adminToken(admin), mapOf("email" to existing.email.uppercase(), "name" to "김초대"))

        assertEquals(409, response.status)
        assertEquals("DUPLICATE_EMAIL", json(response)["code"])
        assertTrue(mails.sent.isEmpty())
        assertTrue(audits(existing.id).isEmpty())
    }

    @Test
    fun `GOV-15 지정할 수 없는 role은 이메일 중복보다 먼저 FORBIDDEN`() {
        val admin = admin()
        val existing = employees.create()

        val response = invite(adminToken(admin), mapOf("email" to existing.email, "name" to "김초대", "roles" to listOf("auth:admin")))

        assertEquals("FORBIDDEN", json(response)["code"])
    }

    @Test
    fun `GOV-14 auth owner·admin role이 없는 직원은 403 FORBIDDEN`() {
        val employee = employees.create()
        // aud에 auth가 있도록 auth audience의 일반 role을 담습니다
        val token = tokens.issue(employee.key, roles = listOf(employees.newRoleCode("auth").value))
        val email = newEmail()

        val response = invite(token, mapOf("email" to email, "name" to "김초대"))

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
        assertNothingCreated(email)
    }

    @Test
    fun `GOV-14 토큰에 admin role이 있어도 DB에서 회수됐으면 FORBIDDEN`() {
        val revoked = employees.create()
        val email = newEmail()

        val response = invite(adminToken(revoked), mapOf("email" to email, "name" to "김초대"))

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
        assertNothingCreated(email)
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        val response = invite(null, mapOf("email" to newEmail(), "name" to "김초대"))

        assertEquals(401, response.status)
        assertEquals("UNAUTHENTICATED", json(response)["code"])
    }

    @Test
    fun `이메일·이름·role 형식이 틀리면 VALIDATION_FAILED이고 아무것도 만들지 않음`() {
        val admin = admin()
        val email = newEmail()
        val invalidBodies =
            listOf(
                mapOf("name" to "김초대"),
                mapOf("email" to "not-an-email", "name" to "김초대"),
                mapOf("email" to "a b@dozycoffee.test", "name" to "김초대"),
                mapOf("email" to "${"a".repeat(250)}@dozycoffee.test", "name" to "김초대"),
                mapOf("email" to email),
                mapOf("email" to email, "name" to " "),
                mapOf("email" to email, "name" to "가".repeat(51)),
                mapOf("email" to email, "name" to "김초대", "phone" to "0".repeat(21)),
                mapOf("email" to email, "name" to "김초대", "roles" to listOf("wms")),
                mapOf("email" to email, "name" to "김초대", "roles" to listOf(null)),
            )

        invalidBodies.forEach { body ->
            val response = invite(adminToken(admin), body)
            assertEquals(400, response.status, body.toString())
            assertEquals("VALIDATION_FAILED", json(response)["code"])
        }
        assertNothingCreated(email)
    }

    private fun admin(): CreatedEmployee = employees.create().also(employees::makeAdmin)

    private fun owner(): CreatedEmployee = employees.create().also(employees::makeOwner)

    private fun adminToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:admin"))

    private fun ownerToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:owner"))

    private fun newEmail(): String = "invitee-${UUID.randomUUID()}@dozycoffee.test"

    /** 응답의 principal을 정리 대상에 넣고 id를 돌려줍니다. */
    private fun track(body: Map<String, Any?>): UUID = UUID.fromString(body["principalId"] as String).also(employees::track)

    private fun assertNothingCreated(email: String) {
        assertTrue(employees.inTransaction { loadEmployee.findEmployeeByEmail(Email(email)) == null })
        assertTrue(mails.sent.isEmpty())
        val actions = employees.inTransaction { AuditLogTable.selectAll().map { it[AuditLogTable.action] } }
        assertTrue(actions.none { it == "EMPLOYEE_INVITED" || it == "ROLE_GRANTED" })
    }

    private fun roleCodes(id: UUID): List<String> = employees.inTransaction { loadPrincipalRoles.findRoleCodes(id).map { it.value } }

    private fun audits(principalId: UUID): List<AuditRow> =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .where { (AuditLogTable.targetType eq "PRINCIPAL") and (AuditLogTable.targetId eq principalId.toString()) }
                .orderBy(AuditLogTable.id)
                .map {
                    AuditRow(
                        it[AuditLogTable.action],
                        it[AuditLogTable.actorId],
                        it[AuditLogTable.userAgent],
                        it[AuditLogTable.detail],
                    )
                }
        }

    private data class AuditRow(
        val action: String,
        val actorId: UUID?,
        val userAgent: String?,
        val detail: Map<String, Any?>?,
    )

    private fun invite(
        token: String?,
        body: Map<String, Any?>,
        userAgent: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/admin/employees") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(body)
                token?.let { header("Authorization", "Bearer $it") }
                userAgent?.let { header("User-Agent", it) }
            }.andReturn()
            .response

    private fun verifyInvitation(token: String): MockHttpServletResponse =
        postJson(
            "/realms/internal/invitations/verify",
            mapOf(
                "token" to token,
            ),
        )

    private fun acceptInvitation(token: String): MockHttpServletResponse =
        postJson("/realms/internal/invitations/accept", mapOf("token" to token, "password" to PASSWORD))

    private fun login(email: String): MockHttpServletResponse =
        postJson(
            "/realms/internal/login",
            mapOf(
                "email" to email,
                "password" to PASSWORD,
            ),
        )

    private fun postJson(
        path: String,
        body: Map<String, Any?>,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(body)
            }.andReturn()
            .response

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        const val PASSWORD = "invited-horse-battery"
    }
}
