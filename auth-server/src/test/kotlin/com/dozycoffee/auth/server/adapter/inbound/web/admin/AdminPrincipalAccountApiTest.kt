package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.EmployeeProfileTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PasswordCredentialTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RefreshSessionTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.VerificationTable
import com.dozycoffee.auth.server.application.port.outbound.mail.PasswordResetMail
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.RecordingMailConfig
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import jakarta.servlet.http.Cookie
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.HttpCookie
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 계정 정지·해제·비활성화, 비밀번호 재설정 메일 발송 (api/admin.md §3, ACC-01·03·04·05, CLI-04, VER-01·03, GOV-02·03·14·15, AUD-08).
 * 실제 DB에 커밋하며 확인합니다.
 *
 * 에러 code, 감사 action과 detail, 폐기 사유는 명세의 문자열 그대로 기대값으로 씁니다. 관리자의 관리 등급은 DB의 role로 정하므로
 * 관리자도 DB에 role을 부여하고, 토큰에도 같은 role을 담습니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class, RecordingMailConfig::class)
@ActiveProfiles("test")
class AdminPrincipalAccountApiTest {
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

    private val jsonMapper = JsonMapper.builder().build()

    private val systemClients = mutableListOf<UUID>()

    @BeforeEach
    fun setUp() = mails.sent.clear()

    @AfterEach
    fun cleanUp() {
        if (systemClients.isNotEmpty()) {
            employees.inTransaction { SystemClientTable.deleteWhere { SystemClientTable.principalId inList systemClients } }
            systemClients.clear()
        }
        employees.cleanUp()
    }

    // 인가 (GOV-14)

    @Test
    fun `GOV-14 auth owner·admin role이 없는 직원은 403 FORBIDDEN`() {
        val employee = employees.create()
        // aud에 auth가 있도록 auth audience의 일반 role을 담습니다
        val token = tokens.issue(employee.key, roles = listOf(employees.newRoleCode("auth").value))

        val responses =
            listOf(
                post("suspend", employee.id, token, REASON_BODY),
                post("reactivate", employee.id, token),
                post("deactivate", employee.id, token, REASON_BODY),
                post("password-reset", employee.id, token),
            )

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("FORBIDDEN", json(it)["code"])
        }
        assertEquals(AccountStatus.ACTIVE, employees.account(employee.id).status)
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        val response = post("suspend", UUID.randomUUID(), null, REASON_BODY)

        assertEquals(401, response.status)
        assertEquals("UNAUTHENTICATED", json(response)["code"])
    }

    @Test
    fun `없는 principal이면 404 NOT_FOUND`() {
        val token = adminToken(admin())

        listOf(
            "suspend" to REASON_BODY,
            "reactivate" to null,
            "deactivate" to REASON_BODY,
            "password-reset" to null,
        ).forEach { (action, body) ->
            val response = post(action, UUID.randomUUID(), token, body)
            assertEquals(404, response.status)
            assertEquals("NOT_FOUND", json(response)["code"])
        }
    }

    @Test
    fun `사유가 없거나 비어 있으면 400 VALIDATION_FAILED이고 아무것도 바꾸지 않음`() {
        val token = adminToken(admin())
        val employee = employees.create()

        listOf("suspend", "deactivate").forEach { action ->
            listOf(emptyMap<String, Any?>(), mapOf("reason" to null), mapOf("reason" to "  ")).forEach { body ->
                val response = post(action, employee.id, token, body)
                assertEquals(400, response.status)
                assertEquals("VALIDATION_FAILED", json(response)["code"])
            }
        }
        assertEquals(AccountStatus.ACTIVE, employees.account(employee.id).status)
    }

    // 계정 정지

    @Test
    fun `ACC-03 정지하면 SUSPENDED로 바꾸고 모든 세션을 폐기해 갱신은 401, 로그인은 ACCOUNT_SUSPENDED`() {
        val admin = admin()
        val employee = employees.create()
        val refreshToken = login(employee.email)

        val response = post("suspend", employee.id, adminToken(admin), REASON_BODY)

        assertEquals(204, response.status)
        assertEquals(AccountStatus.SUSPENDED, employees.account(employee.id).status)
        assertEquals(listOf("ACCOUNT_SUSPENDED"), sessionRevokeReasons(employee.id))
        val refreshed = refresh(refreshToken)
        assertEquals(401, refreshed.status)
        assertEquals("SESSION_EXPIRED", json(refreshed)["code"])
        val loggedIn = loginResponse(employee.email)
        assertEquals(403, loggedIn.status)
        assertEquals("ACCOUNT_SUSPENDED", json(loggedIn)["code"])
    }

    @Test
    fun `ACC-03 정지는 ACCOUNT_SUSPENDED에 사유와 폐기한 세션 수를 남김`() {
        val admin = admin()
        val employee = employees.create()
        login(employee.email)

        post("suspend", employee.id, adminToken(admin), REASON_BODY)

        val audit = audits(employee.id).single()
        assertEquals("ACCOUNT_SUSPENDED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(mapOf("reason" to REASON, "revokedSessions" to 1), audit.detail)
    }

    @Test
    fun `폐기한 세션이 없으면 정지 감사 로그에 사유만 남김`() {
        val admin = admin()
        val employee = employees.create()

        post("suspend", employee.id, adminToken(admin), REASON_BODY)

        assertEquals(mapOf("reason" to REASON), audits(employee.id).single().detail)
    }

    @Test
    fun `ACC-01 ACTIVE가 아닌 계정의 정지는 409 INVALID_STATE이고 감사 로그를 남기지 않음`() {
        val token = adminToken(admin())

        listOf(AccountStatus.PENDING, AccountStatus.SUSPENDED, AccountStatus.DEACTIVATED).forEach { status ->
            val employee = employees.create(status = status)

            val response = post("suspend", employee.id, token, REASON_BODY)

            assertEquals(409, response.status)
            assertEquals("INVALID_STATE", json(response)["code"])
            assertEquals(status, employees.account(employee.id).status)
            assertEquals(emptyList(), audits(employee.id))
        }
    }

    @Test
    fun `GOV-03 owner는 owner 자신도 정지·비활성화할 수 없어 PROTECTED_ACCOUNT`() {
        val owner = owner()
        val admin = admin()

        val responses =
            listOf(ownerToken(owner), adminToken(admin)).flatMap { token ->
                listOf(post("suspend", owner.id, token, REASON_BODY), post("deactivate", owner.id, token, REASON_BODY))
            }

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("PROTECTED_ACCOUNT", json(it)["code"])
        }
        assertEquals(AccountStatus.ACTIVE, employees.account(owner.id).status)
    }

    @Test
    fun `GOV-02 admin은 다른 admin을 정지·해제·비활성화하거나 재설정 메일을 보낼 수 없음`() {
        val admin = admin()
        val otherAdmin = admin()
        val suspendedAdmin = employees.create(status = AccountStatus.SUSPENDED).also(employees::makeAdmin)
        val token = adminToken(admin)

        val responses =
            listOf(
                post("suspend", otherAdmin.id, token, REASON_BODY),
                post("reactivate", suspendedAdmin.id, token),
                post("deactivate", otherAdmin.id, token, REASON_BODY),
                post("password-reset", otherAdmin.id, token),
            )

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("PROTECTED_ACCOUNT", json(it)["code"])
        }
        assertEquals(AccountStatus.ACTIVE, employees.account(otherAdmin.id).status)
        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `GOV-15 admin이 정지된 admin을 정지하면 상태보다 보호 규칙이 먼저라 PROTECTED_ACCOUNT`() {
        val admin = admin()
        val suspendedAdmin = employees.create(status = AccountStatus.SUSPENDED).also(employees::makeAdmin)

        val response = post("suspend", suspendedAdmin.id, adminToken(admin), REASON_BODY)

        assertEquals(403, response.status)
        assertEquals("PROTECTED_ACCOUNT", json(response)["code"])
    }

    @Test
    fun `GOV-02 owner는 admin을 정지할 수 있음`() {
        val owner = owner()
        val admin = admin()

        val response = post("suspend", admin.id, ownerToken(owner), REASON_BODY)

        assertEquals(204, response.status)
        assertEquals(AccountStatus.SUSPENDED, employees.account(admin.id).status)
    }

    @Test
    fun `system client와 파트너도 정지할 수 있음`() {
        val token = adminToken(admin())
        val client = systemClient().principalId
        val partner = principal(PrincipalType.PARTNER, AccountStatus.ACTIVE)

        listOf(client, partner).forEach { id ->
            assertEquals(204, post("suspend", id, token, REASON_BODY).status)
            assertEquals(AccountStatus.SUSPENDED, employees.account(id).status)
        }
    }

    // 정지 해제

    @Test
    fun `정지를 해제하면 ACTIVE로 돌아와 다시 로그인할 수 있고 ACCOUNT_REACTIVATED를 남김`() {
        val admin = admin()
        val employee = employees.create()
        post("suspend", employee.id, adminToken(admin), REASON_BODY)

        val response = post("reactivate", employee.id, adminToken(admin))

        assertEquals(204, response.status)
        assertEquals(AccountStatus.ACTIVE, employees.account(employee.id).status)
        assertEquals(200, loginResponse(employee.email).status)
        val audit = audits(employee.id).filter { it.action == "ACCOUNT_REACTIVATED" }.single()
        assertEquals(admin.id, audit.actorId)
        assertNull(audit.detail)
    }

    @Test
    fun `ACC-01 SUSPENDED가 아닌 계정의 해제는 409 INVALID_STATE`() {
        val token = adminToken(admin())

        listOf(AccountStatus.PENDING, AccountStatus.ACTIVE, AccountStatus.DEACTIVATED).forEach { status ->
            val employee = employees.create(status = status)

            val response = post("reactivate", employee.id, token)

            assertEquals(409, response.status)
            assertEquals("INVALID_STATE", json(response)["code"])
            assertEquals(status, employees.account(employee.id).status)
        }
    }

    // 계정 비활성화

    @Test
    fun `ACC-04 직원을 비활성화하면 세션, 비밀번호, role, verification을 정리하고 개인정보를 파기함`() {
        val admin = admin()
        val employee = employees.create(roles = listOf(employees.newRoleCode("wms")), phone = "010-1234-5678")
        login(employee.email)
        val reset = issueVerification(employee)

        val response = post("deactivate", employee.id, adminToken(admin), REASON_BODY)

        assertEquals(204, response.status)
        val account = employees.account(employee.id)
        assertEquals(AccountStatus.DEACTIVATED, account.status)
        assertNotNull(account.deactivatedAt)
        assertEquals(listOf("ACCOUNT_DEACTIVATED"), sessionRevokeReasons(employee.id))
        val profile =
            employees.inTransaction {
                EmployeeProfileTable.selectAll().where { EmployeeProfileTable.principalId eq employee.id }.single()
            }
        assertEquals("deleted+${employee.id}@invalid.local", profile[EmployeeProfileTable.email])
        assertEquals("탈퇴 사용자", profile[EmployeeProfileTable.name])
        assertNull(profile[EmployeeProfileTable.phone])
        assertEquals(0L, count { PasswordCredentialTable.selectAll().where { PasswordCredentialTable.principalId eq employee.id } })
        assertEquals(0L, count { PrincipalRoleTable.selectAll().where { PrincipalRoleTable.principalId eq employee.id } })
        assertNotNull(
            employees.inTransaction {
                VerificationTable.selectAll().where { VerificationTable.id eq reset }.single()[VerificationTable.invalidatedAt]
            },
        )
        val loggedIn = loginResponse(employee.email)
        assertEquals(401, loggedIn.status)
        assertEquals("INVALID_CREDENTIALS", json(loggedIn)["code"])
    }

    @Test
    fun `ACC-04 비활성화는 ACCOUNT_DEACTIVATED에 사유와 폐기한 세션 수를 남기고 ROLE_REVOKED는 남기지 않음`() {
        val admin = admin()
        val employee = employees.create(roles = listOf(employees.newRoleCode("wms")))
        login(employee.email)

        post("deactivate", employee.id, adminToken(admin), REASON_BODY)

        val audit = audits(employee.id).filter { it.action.startsWith("ACCOUNT_") || it.action.startsWith("ROLE_") }.single()
        assertEquals("ACCOUNT_DEACTIVATED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(mapOf("reason" to REASON, "revokedSessions" to 1), audit.detail)
    }

    @Test
    fun `ACC-05 비활성화한 직원의 이메일로 다시 초대할 수 있음`() {
        val admin = admin()
        val employee = employees.create()
        post("deactivate", employee.id, adminToken(admin), REASON_BODY)

        val invited =
            mockMvc
                .post("/admin/employees") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${adminToken(admin)}")
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(mapOf("email" to employee.email, "name" to "김재입사"))
                }.andReturn()
                .response

        assertEquals(201, invited.status)
        employees.track(UUID.fromString(json(invited)["principalId"] as String))
    }

    @Test
    fun `PENDING 계정도 비활성화할 수 있음`() {
        val token = adminToken(admin())
        val employee = employees.create(status = AccountStatus.PENDING, password = null)
        val partner = principal(PrincipalType.PARTNER, AccountStatus.PENDING)

        listOf(employee.id, partner).forEach { id ->
            assertEquals(204, post("deactivate", id, token, REASON_BODY).status)
            assertEquals(AccountStatus.DEACTIVATED, employees.account(id).status)
        }
    }

    @Test
    fun `정지된 계정도 비활성화할 수 있음`() {
        val employee = employees.create(status = AccountStatus.SUSPENDED)

        assertEquals(204, post("deactivate", employee.id, adminToken(admin()), REASON_BODY).status)
        assertEquals(AccountStatus.DEACTIVATED, employees.account(employee.id).status)
    }

    @Test
    fun `ACC-01 이미 DEACTIVATED인 계정의 비활성화는 409 INVALID_STATE`() {
        val employee = employees.create(status = AccountStatus.DEACTIVATED)

        val response = post("deactivate", employee.id, adminToken(admin()), REASON_BODY)

        assertEquals(409, response.status)
        assertEquals("INVALID_STATE", json(response)["code"])
        assertEquals(emptyList(), audits(employee.id))
    }

    @Test
    fun `CLI-04 system client를 비활성화하면 client_id가 deleted-id로 바뀌고 secret이 지워져 토큰 발급은 invalid_client`() {
        val client = systemClient()
        assertEquals(200, requestSystemToken(client.clientId, client.secret).status)

        val response = post("deactivate", client.principalId, adminToken(admin()), REASON_BODY)

        assertEquals(204, response.status)
        val row =
            employees.inTransaction {
                SystemClientTable.selectAll().where { SystemClientTable.principalId eq client.principalId }.single()
            }
        assertEquals("deleted-${client.principalId}", row[SystemClientTable.clientId])
        assertNull(row[SystemClientTable.clientSecretHash])
        val token = requestSystemToken(client.clientId, client.secret)
        assertEquals(401, token.status)
        assertEquals("invalid_client", json(token)["error"])
        // 같은 client_id로 다시 등록할 수 있음
        systemClient(clientId = client.clientId)
    }

    // 비밀번호 재설정 메일 발송

    @Test
    fun `VER-01 재설정 메일 발송은 202이고 PASSWORD_RESET을 발급해 그 토큰을 담은 메일을 커밋 후 보냄`() {
        val admin = admin()
        val employee = employees.create()

        val response = post("password-reset", employee.id, adminToken(admin))

        assertEquals(202, response.status)
        assertEquals("", response.contentAsString)
        val mail = mails.sent.single() as PasswordResetMail
        assertEquals(Email(employee.email), mail.to)
        assertEquals(Realm.INTERNAL, mail.realm)
        val verification =
            employees.inTransaction {
                VerificationTable
                    .selectAll()
                    .where { (VerificationTable.principalId eq employee.id) and (VerificationTable.purpose eq "PASSWORD_RESET") }
                    .single()
            }
        assertEquals(mail.token.hash().hex, verification[VerificationTable.tokenHash])
        assertEquals(employee.email, verification[VerificationTable.target])
        assertEquals(mail.expiresAt, verification[VerificationTable.expiresAt])
        assertEquals(
            AuthPolicy.PASSWORD_RESET_TTL,
            Duration.between(verification[VerificationTable.createdAt], verification[VerificationTable.expiresAt]),
        )
    }

    @Test
    fun `VER-03 재설정 메일을 다시 보내면 이전 토큰은 무효화됨`() {
        val token = adminToken(admin())
        val employee = employees.create()

        post("password-reset", employee.id, token)
        post("password-reset", employee.id, token)

        val rows =
            employees.inTransaction {
                VerificationTable
                    .selectAll()
                    .where { (VerificationTable.principalId eq employee.id) and (VerificationTable.purpose eq "PASSWORD_RESET") }
                    .orderBy(VerificationTable.id)
                    .map { it[VerificationTable.tokenHash] to it[VerificationTable.invalidatedAt] }
            }
        assertEquals(2, rows.size)
        assertNotNull(rows[0].second)
        assertNull(rows[1].second)
        assertEquals((mails.sent.last() as PasswordResetMail).token.hash().hex, rows[1].first)
    }

    @Test
    fun `PWD-05 재설정 메일을 보내도 비밀번호와 세션은 그대로이고 PASSWORD_RESET_REQUESTED를 남김`() {
        val admin = admin()
        val employee = employees.create()
        val refreshToken = login(employee.email)

        post("password-reset", employee.id, adminToken(admin))

        assertEquals(200, refresh(refreshToken).status)
        assertEquals(200, loginResponse(employee.email).status)
        val audit = audits(employee.id).single { it.action == "PASSWORD_RESET_REQUESTED" }
        assertEquals(admin.id, audit.actorId)
        assertNull(audit.detail)
    }

    @Test
    fun `ACTIVE가 아닌 계정이나 system client에는 재설정 메일을 보내지 않고 409 INVALID_STATE`() {
        val token = adminToken(admin())
        val targets =
            listOf(AccountStatus.PENDING, AccountStatus.SUSPENDED, AccountStatus.DEACTIVATED).map { employees.create(status = it).id } +
                systemClient().principalId

        targets.forEach { id ->
            val response = post("password-reset", id, token)
            assertEquals(409, response.status)
            assertEquals("INVALID_STATE", json(response)["code"])
            assertEquals(emptyList(), audits(id))
        }
        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `GOV-02 owner는 admin에게 재설정 메일을 보낼 수 있음`() {
        val owner = owner()
        val admin = admin()

        assertEquals(202, post("password-reset", admin.id, ownerToken(owner)).status)
        assertEquals(Email(admin.email), mails.sent.single().to)
    }

    private fun admin(): CreatedEmployee = employees.create().also(employees::makeAdmin)

    private fun owner(): CreatedEmployee = employees.create().also(employees::makeOwner)

    private fun adminToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:admin"))

    private fun ownerToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:owner"))

    /** 직원이 아닌 principal (파트너). 규칙 검사에 필요한 principal 행만 만듭니다. */
    private fun principal(
        type: PrincipalType,
        status: AccountStatus,
    ): UUID =
        employees
            .inTransaction {
                PrincipalTable
                    .insertReturning(listOf(PrincipalTable.id)) {
                        it[PrincipalTable.type] = type.name
                        it[PrincipalTable.status] = status.name
                    }.single()[PrincipalTable.id]
            }.also { employees.track(it) }

    /** `ACTIVE` system client를 등록합니다. */
    private fun systemClient(clientId: String = "svc-it-${UUID.randomUUID().toString().replace("-", "").take(12)}"): RegisteredClient {
        val principalId = principal(PrincipalType.SYSTEM, AccountStatus.ACTIVE)
        val secret = OpaqueSecret.generate().value
        employees.inTransaction {
            SystemClientTable.insert {
                it[SystemClientTable.principalId] = principalId
                it[SystemClientTable.clientId] = clientId
                it[clientSecretHash] = SecretHash.of(secret).hex
                it[name] = "통합 테스트"
                it[secretRotatedAt] = clock.instant()
            }
        }
        systemClients += principalId
        return RegisteredClient(principalId, clientId, secret)
    }

    /** [employee]에게 살아 있는 `PASSWORD_RESET`을 발급합니다. verification id를 돌려줍니다. */
    private fun issueVerification(employee: CreatedEmployee): Long {
        post("password-reset", employee.id, adminToken(admin()))
        return employees.inTransaction {
            VerificationTable.selectAll().where { VerificationTable.principalId eq employee.id }.single()[VerificationTable.id]
        }
    }

    private fun count(query: () -> Query): Long = employees.inTransaction { query().count() }

    private fun post(
        action: String,
        principalId: UUID,
        token: String?,
        body: Map<String, Any?>? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/admin/principals/$principalId/$action") {
                token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(it)
                }
            }.andReturn()
            .response

    private fun loginResponse(email: String): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/login") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to TestEmployees.PASSWORD))
            }.andReturn()
            .response

    /** 로그인하고 refresh token을 돌려줍니다. */
    private fun login(email: String): String {
        val response = loginResponse(email)
        check(response.status == 200) { "로그인 실패: ${response.status}" }
        return HttpCookie.parse(checkNotNull(response.getHeader("Set-Cookie"))).single().value
    }

    private fun refresh(refreshToken: String): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/token/refresh") {
                cookie(Cookie("dozy_refresh", refreshToken))
                header("Origin", ALLOWED_ORIGIN)
            }.andReturn()
            .response

    private fun requestSystemToken(
        clientId: String,
        secret: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/token") {
                header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray()))
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = "grant_type=client_credentials"
            }.andReturn()
            .response

    private fun sessionRevokeReasons(principalId: UUID): List<String?> =
        employees.inTransaction {
            RefreshSessionTable
                .selectAll()
                .where { RefreshSessionTable.principalId eq principalId }
                .map { it[RefreshSessionTable.revokeReason] }
        }

    private fun audits(principalId: UUID): List<AuditRow> =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .where { (AuditLogTable.targetType eq "PRINCIPAL") and (AuditLogTable.targetId eq principalId.toString()) }
                .orderBy(AuditLogTable.id)
                .map { AuditRow(it[AuditLogTable.action], it[AuditLogTable.actorId], it[AuditLogTable.detail]) }
                // 로그인 기록은 이 API가 남긴 것이 아니므로 뺍니다
                .filterNot { it.action.startsWith("LOGIN_") }
        }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private data class AuditRow(
        val action: String,
        val actorId: UUID?,
        val detail: Map<String, Any?>?,
    )

    private data class RegisteredClient(
        val principalId: UUID,
        val clientId: String,
        val secret: String,
    )

    private companion object {
        /** test 프로필의 CORS 허용 origin (`application-test.yaml`). */
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
        const val REASON = "퇴사 처리"
        val REASON_BODY = mapOf("reason" to REASON)
    }
}
