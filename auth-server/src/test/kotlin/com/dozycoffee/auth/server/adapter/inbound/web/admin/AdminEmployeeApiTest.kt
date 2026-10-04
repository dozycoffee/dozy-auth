package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.EmployeeProfileTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.VerificationTable
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.RecordingMailConfig
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 직원 목록·상세·정보 수정, 초대 재발송·취소 (api/admin.md §1, ACC-04~07, VER-03, GOV-02·03·14·15, AUD-07·08). 실제 DB에 커밋하며 확인합니다.
 *
 * 응답 필드 이름, 에러 code, 감사 action과 detail은 명세의 문자열 그대로 기대값으로 씁니다. 관리자의 관리 등급은 DB의 role로 정하므로
 * 관리자도 DB에 role을 부여하고, 토큰에도 같은 role을 담습니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class, RecordingMailConfig::class)
@ActiveProfiles("test")
class AdminEmployeeApiTest {
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
    lateinit var clock: Clock

    private val jsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() = mails.sent.clear()

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    // 인가 (GOV-14)

    @Test
    fun `GOV-14 auth owner·admin role이 없는 직원은 403 FORBIDDEN`() {
        val employee = employees.create()
        // aud에 auth가 있도록 auth audience의 일반 role을 담습니다
        val token = tokens.issue(employee.key, roles = listOf(employees.newRoleCode("auth").value))

        val responses =
            listOf(
                request(HttpMethod.GET, "/admin/employees", token),
                request(HttpMethod.GET, "/admin/employees/${employee.id}", token),
                request(HttpMethod.PATCH, "/admin/employees/${employee.id}", token, mapOf("name" to "변경")),
                request(HttpMethod.POST, "/admin/employees/${employee.id}/invitation", token),
                request(HttpMethod.DELETE, "/admin/employees/${employee.id}/invitation", token),
            )

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("FORBIDDEN", json(it)["code"])
        }
    }

    @Test
    fun `토큰이 없거나 aud에 auth가 없으면 401 UNAUTHENTICATED`() {
        val employee = employees.create()
        val withoutAuthAudience = tokens.issue(employee.key, roles = listOf(employees.newRoleCode("wms").value))

        listOf(null, withoutAuthAudience).forEach { token ->
            val response = request(HttpMethod.GET, "/admin/employees", token)
            assertEquals(401, response.status)
            assertEquals("UNAUTHENTICATED", json(response)["code"])
        }
    }

    @Test
    fun `토큰에 admin role이 있어도 DB에서 회수됐으면 변경은 FORBIDDEN`() {
        val revoked = employees.create()
        val target = employees.create(status = AccountStatus.PENDING, password = null)

        val response = request(HttpMethod.PATCH, "/admin/employees/${target.id}", adminToken(revoked), mapOf("name" to "변경"))

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
    }

    // 목록

    @Test
    fun `이름 또는 이메일에 검색어가 들어간 직원을 대소문자 무시하고 찾음`() {
        val admin = admin()
        val keyword = uniqueKeyword()
        val byName = employees.create(name = "김$keyword")
        val other = employees.create(name = "이도윤")

        val body = json(request(HttpMethod.GET, "/admin/employees?q=${keyword.uppercase()}", adminToken(admin)))

        assertEquals(listOf(byName.id.toString()), ids(body))
        val byEmail = json(request(HttpMethod.GET, "/admin/employees?q=${other.email.substring(0, 20)}", adminToken(admin)))
        assertEquals(listOf(other.id.toString()), ids(byEmail))
    }

    @Test
    fun `검색어의 퍼센트와 밑줄은 글자 그대로 찾음`() {
        val admin = admin()
        val keyword = uniqueKeyword()
        val literal = employees.create(name = "${keyword}_%")
        employees.create(name = "${keyword}ab")

        val body = json(request(HttpMethod.GET, "/admin/employees?q=${keyword}_%25", adminToken(admin)))

        assertEquals(listOf(literal.id.toString()), ids(body))
    }

    @Test
    fun `목록 항목은 명세의 필드와 DB의 현재 role을 담음`() {
        val admin = admin()
        val keyword = uniqueKeyword()
        val role = employees.newRoleCode("wms")
        val employee = employees.create(name = keyword, roles = listOf(role))

        val body = json(request(HttpMethod.GET, "/admin/employees?q=$keyword", adminToken(admin)))

        @Suppress("UNCHECKED_CAST")
        val item = (body["items"] as List<Map<String, Any?>>).single()
        assertEquals(setOf("principalId", "email", "name", "status", "roles", "createdAt"), item.keys)
        assertEquals(employee.id.toString(), item["principalId"])
        assertEquals(employee.email, item["email"])
        assertEquals(keyword, item["name"])
        assertEquals("ACTIVE", item["status"])
        assertEquals(listOf(role.value), item["roles"])
        assertNotNull(Instant.parse(item["createdAt"] as String))
    }

    @Test
    fun `목록을 페이지로 나누고 생성 최신순으로 돌려줌`() {
        val admin = admin()
        val keyword = uniqueKeyword()
        val first = employees.create(name = "${keyword}1")
        val second = employees.create(name = "${keyword}2")
        val third = employees.create(name = "${keyword}3")

        val page0 = json(request(HttpMethod.GET, "/admin/employees?q=$keyword&size=2", adminToken(admin)))
        val page1 = json(request(HttpMethod.GET, "/admin/employees?q=$keyword&size=2&page=1", adminToken(admin)))

        assertEquals(mapOf("number" to 0, "size" to 2, "totalElements" to 3, "totalPages" to 2), page0["page"])
        assertEquals(mapOf("number" to 1, "size" to 2, "totalElements" to 3, "totalPages" to 2), page1["page"])
        // 생성 시각이 같으므로(fixture) id(UUIDv7) 역순
        assertEquals(listOf(third, second, first).map { it.id.toString() }, ids(page0) + ids(page1))
    }

    @Test
    fun `상태와 role로 목록을 거름`() {
        val admin = admin()
        val keyword = uniqueKeyword()
        val role = employees.newRoleCode("wms")
        val pending = employees.create(status = AccountStatus.PENDING, password = null, name = "${keyword}1")
        val withRole = employees.create(name = "${keyword}2", roles = listOf(role))
        employees.create(name = "${keyword}3")

        val byStatus = json(request(HttpMethod.GET, "/admin/employees?q=$keyword&status=PENDING", adminToken(admin)))
        val byRole = json(request(HttpMethod.GET, "/admin/employees?q=$keyword&role=${role.value}", adminToken(admin)))
        val unknownRole = json(request(HttpMethod.GET, "/admin/employees?q=$keyword&role=wms:no_such_role", adminToken(admin)))

        assertEquals(listOf(pending.id.toString()), ids(byStatus))
        assertEquals(listOf(withRole.id.toString()), ids(byRole))
        assertEquals(emptyList(), ids(unknownRole))
    }

    @Test
    fun `페이지 크기가 상한을 넘거나 role 형식이 틀리면 VALIDATION_FAILED`() {
        val admin = admin()

        listOf("size=${MAX_PAGE_SIZE + 1}", "page=-1", "role=WMS", "status=UNKNOWN").forEach { query ->
            val response = request(HttpMethod.GET, "/admin/employees?$query", adminToken(admin))
            assertEquals(400, response.status, query)
            assertEquals("VALIDATION_FAILED", json(response)["code"], query)
        }
    }

    // 상세

    @Test
    fun `직원 상세는 명세의 필드를 응답하고 PENDING이면 초대 만료 시각을 담음`() {
        val admin = admin()
        val role = employees.newRoleCode("catalog")
        val employee = employees.create(status = AccountStatus.PENDING, password = null, roles = listOf(role), phone = "010-1234-5678")
        val issuedAt = now()
        employees.issueInvitation(employee, issuedAt)

        val body = json(request(HttpMethod.GET, "/admin/employees/${employee.id}", adminToken(admin)))

        assertEquals(
            setOf(
                "principalId",
                "email",
                "name",
                "phone",
                "address",
                "status",
                "roles",
                "lockedUntil",
                "invitation",
                "createdAt",
                "updatedAt",
            ),
            body.keys,
        )
        assertEquals(employee.email, body["email"])
        assertEquals("010-1234-5678", body["phone"])
        assertNull(body["address"])
        assertEquals("PENDING", body["status"])
        assertEquals(listOf(role.value), body["roles"])
        assertNull(body["lockedUntil"])
        assertEquals(mapOf("expiresAt" to issuedAt.plus(AuthPolicy.INVITATION_TTL).toString()), body["invitation"])
    }

    @Test
    fun `PENDING이 아니면 invitation은 null이고 admin도 owner를 조회할 수 있음`() {
        val admin = admin()
        val owner = owner()

        val body = json(request(HttpMethod.GET, "/admin/employees/${owner.id}", adminToken(admin)))

        assertEquals("ACTIVE", body["status"])
        assertTrue(body.containsKey("invitation"))
        assertNull(body["invitation"])
        assertEquals(listOf("auth:owner"), body["roles"])
    }

    @Test
    fun `없는 직원이면 NOT_FOUND`() {
        val response = request(HttpMethod.GET, "/admin/employees/${UUID.randomUUID()}", adminToken(admin()))

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", json(response)["code"])
    }

    // 정보 수정

    @Test
    fun `ACC-07 보낸 필드만 수정하고 null이면 삭제하며 수정 결과를 상세 형식으로 응답`() {
        val admin = admin()
        val employee = employees.create(name = "김도윤", phone = "010-1234-5678")

        val response =
            request(
                HttpMethod.PATCH,
                "/admin/employees/${employee.id}",
                adminToken(admin),
                mapOf("name" to "김도윤2", "phone" to null),
            )

        assertEquals(200, response.status)
        val body = json(response)
        assertEquals("김도윤2", body["name"])
        assertNull(body["phone"])
        assertEquals(employee.email, body["email"])
        val profile = checkNotNull(loadProfile(employee)).profile
        assertEquals("김도윤2", profile.name)
        assertNull(profile.phone)
    }

    @Test
    fun `AUD-07 정보 수정은 바뀐 필드 이름만 PROFILE_UPDATED로 남김`() {
        val admin = admin()
        val employee = employees.create(name = "김도윤", phone = "010-1234-5678")

        request(
            HttpMethod.PATCH,
            "/admin/employees/${employee.id}",
            adminToken(admin),
            mapOf("name" to "김도윤", "phone" to "010-9999-8888", "address" to "서울시 성동구"),
            userAgent = "DozyConsole/1.0",
        )

        val audit =
            employees.inTransaction {
                AuditLogTable.selectAll().where { AuditLogTable.targetId eq employee.id.toString() }.single()
            }
        assertEquals("PROFILE_UPDATED", audit[AuditLogTable.action])
        assertEquals(admin.id, audit[AuditLogTable.actorId])
        assertEquals(mapOf("fields" to listOf("phone", "address")), audit[AuditLogTable.detail])
        assertEquals("DozyConsole/1.0", audit[AuditLogTable.userAgent])
    }

    @Test
    fun `바뀐 값이 없으면 감사 로그를 남기지 않음`() {
        val admin = admin()
        val employee = employees.create(name = "김도윤")

        val response = request(HttpMethod.PATCH, "/admin/employees/${employee.id}", adminToken(admin), mapOf("name" to "김도윤"))

        assertEquals(200, response.status)
        assertEquals(emptyList(), employees.auditActions(employee.id))
    }

    @Test
    fun `ACC-07 이메일을 보내면 VALIDATION_FAILED이고 아무것도 바꾸지 않음`() {
        val admin = admin()
        val employee = employees.create(name = "김도윤")

        val response =
            request(
                HttpMethod.PATCH,
                "/admin/employees/${employee.id}",
                adminToken(admin),
                mapOf("email" to "changed@dozycoffee.test", "name" to "변경"),
            )

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
        val profile = checkNotNull(loadProfile(employee)).profile
        assertEquals(employee.email, profile.email.value)
        assertEquals("김도윤", profile.name)
    }

    @Test
    fun `이름을 null이나 빈 값으로 보내면 VALIDATION_FAILED`() {
        val admin = admin()
        val employee = employees.create()

        listOf(mapOf("name" to null), mapOf("name" to " ")).forEach { body ->
            val response = request(HttpMethod.PATCH, "/admin/employees/${employee.id}", adminToken(admin), body)
            assertEquals(400, response.status, body.toString())
            assertEquals("VALIDATION_FAILED", json(response)["code"])
        }
    }

    @Test
    fun `DEACTIVATED 직원의 정보 수정은 INVALID_STATE`() {
        val admin = admin()
        val employee = employees.create(status = AccountStatus.DEACTIVATED, password = null)

        val response = request(HttpMethod.PATCH, "/admin/employees/${employee.id}", adminToken(admin), mapOf("name" to "변경"))

        assertEquals(409, response.status)
        assertEquals("INVALID_STATE", json(response)["code"])
    }

    @Test
    fun `GOV-02 admin은 owner와 다른 admin의 정보를 수정할 수 없음`() {
        val admin = admin()
        val otherAdmin = admin()
        val owner = owner()

        listOf(otherAdmin, owner, admin).forEach { target ->
            val response = request(HttpMethod.PATCH, "/admin/employees/${target.id}", adminToken(admin), mapOf("name" to "변경"))
            assertEquals(403, response.status)
            assertEquals("PROTECTED_ACCOUNT", json(response)["code"])
        }
    }

    @Test
    fun `GOV-02 owner는 admin의 정보를 수정할 수 있음`() {
        val owner = owner()
        val admin = admin()

        val response = request(HttpMethod.PATCH, "/admin/employees/${admin.id}", ownerToken(owner), mapOf("name" to "변경"))

        assertEquals(200, response.status)
    }

    @Test
    fun `GOV-15 없는 직원은 보호 규칙보다 먼저 NOT_FOUND`() {
        val response = request(HttpMethod.PATCH, "/admin/employees/${UUID.randomUUID()}", adminToken(admin()), mapOf("name" to "변경"))

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", json(response)["code"])
    }

    // 초대 재발송

    @Test
    fun `VER-03 초대를 재발송하면 이전 초대는 쓸 수 없고 새 초대 메일을 보냄`() {
        val admin = admin()
        val employee = employees.create(status = AccountStatus.PENDING, password = null, name = "김초대")
        val previous = employees.issueInvitation(employee, now())

        val response = request(HttpMethod.POST, "/admin/employees/${employee.id}/invitation", adminToken(admin))

        assertEquals(202, response.status)
        val expiresAt = Instant.parse(json(response)["invitationExpiresAt"] as String)
        val mail = mails.sent.single() as EmployeeInvitationMail
        assertEquals(Email(employee.email), mail.to)
        assertEquals("김초대", mail.name)
        assertEquals(expiresAt, mail.expiresAt)
        assertEquals(410, verifyInvitation(previous).status)
        assertEquals(200, verifyInvitation(mail.token.value).status)
    }

    @Test
    fun `AUD-08 초대 재발송은 감사 로그를 남기지 않음`() {
        val admin = admin()
        val employee = employees.create(status = AccountStatus.PENDING, password = null)

        request(HttpMethod.POST, "/admin/employees/${employee.id}/invitation", adminToken(admin))

        assertEquals(emptyList(), employees.auditActions(employee.id))
    }

    @Test
    fun `PENDING이 아닌 직원의 초대 재발송은 INVALID_STATE이고 메일을 보내지 않음`() {
        val admin = admin()
        val employee = employees.create(status = AccountStatus.ACTIVE)

        val response = request(HttpMethod.POST, "/admin/employees/${employee.id}/invitation", adminToken(admin))

        assertEquals(409, response.status)
        assertEquals("INVALID_STATE", json(response)["code"])
        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `GOV-15 admin이 ACTIVE admin의 초대를 재발송하면 상태보다 보호 규칙이 먼저라 PROTECTED_ACCOUNT`() {
        val admin = admin()
        val otherAdmin = admin()

        val response = request(HttpMethod.POST, "/admin/employees/${otherAdmin.id}/invitation", adminToken(admin))

        assertEquals(403, response.status)
        assertEquals("PROTECTED_ACCOUNT", json(response)["code"])
    }

    // 초대 취소

    @Test
    fun `ACC-06 초대를 취소하면 DEACTIVATED로 바꾸고 ACC-04에 따라 role, 초대, 개인정보를 정리함`() {
        val admin = admin()
        val role = employees.newRoleCode("wms")
        val employee = employees.create(status = AccountStatus.PENDING, password = null, roles = listOf(role), phone = "010-1234-5678")
        val token = employees.issueInvitation(employee, now())

        val response = request(HttpMethod.DELETE, "/admin/employees/${employee.id}/invitation", adminToken(admin))

        assertEquals(204, response.status)
        val account = employees.account(employee.id)
        assertEquals(AccountStatus.DEACTIVATED, account.status)
        assertNotNull(account.deactivatedAt)
        val profile =
            employees.inTransaction {
                EmployeeProfileTable.selectAll().where { EmployeeProfileTable.principalId eq employee.id }.single()
            }
        assertEquals("deleted+${employee.id}@invalid.local", profile[EmployeeProfileTable.email])
        assertEquals("탈퇴 사용자", profile[EmployeeProfileTable.name])
        assertNull(profile[EmployeeProfileTable.phone])
        assertEquals(
            0L,
            employees.inTransaction { PrincipalRoleTable.selectAll().where { PrincipalRoleTable.principalId eq employee.id }.count() },
        )
        assertNotNull(
            employees.inTransaction {
                VerificationTable.selectAll().where { VerificationTable.principalId eq employee.id }.single()
            }[VerificationTable.invalidatedAt],
        )
        assertEquals(410, verifyInvitation(token).status)
    }

    @Test
    fun `ACC-06 초대 취소는 ACCOUNT_DEACTIVATED를 via와 함께 남김`() {
        val admin = admin()
        val employee = employees.create(status = AccountStatus.PENDING, password = null)

        request(HttpMethod.DELETE, "/admin/employees/${employee.id}/invitation", adminToken(admin))

        val audit =
            employees.inTransaction {
                AuditLogTable.selectAll().where { AuditLogTable.targetId eq employee.id.toString() }.single()
            }
        assertEquals("ACCOUNT_DEACTIVATED", audit[AuditLogTable.action])
        assertEquals(admin.id, audit[AuditLogTable.actorId])
        assertEquals(mapOf("via" to "invitation_cancelled"), audit[AuditLogTable.detail])
    }

    @Test
    fun `ACC-05 초대를 취소하면 같은 이메일을 다시 쓸 수 있음`() {
        val admin = admin()
        val employee = employees.create(status = AccountStatus.PENDING, password = null)

        request(HttpMethod.DELETE, "/admin/employees/${employee.id}/invitation", adminToken(admin))

        assertFalse(employees.inTransaction { loadEmployee.findEmployeeByEmail(Email(employee.email)) != null })
        val reinvited = employees.create(email = employee.email)
        assertNotEquals(employee.id, reinvited.id)
    }

    @Test
    fun `PENDING이 아닌 직원의 초대 취소는 INVALID_STATE이고 아무것도 바꾸지 않음`() {
        val admin = admin()
        val employee = employees.create(status = AccountStatus.ACTIVE)

        val response = request(HttpMethod.DELETE, "/admin/employees/${employee.id}/invitation", adminToken(admin))

        assertEquals(409, response.status)
        assertEquals("INVALID_STATE", json(response)["code"])
        assertEquals(AccountStatus.ACTIVE, employees.account(employee.id).status)
        assertEquals(employee.email, checkNotNull(loadProfile(employee)).profile.email.value)
        assertEquals(emptyList(), employees.auditActions(employee.id))
    }

    @Test
    fun `GOV-03 owner의 초대는 누구도 취소할 수 없음`() {
        val admin = admin()
        val owner = owner()

        val byAdmin = request(HttpMethod.DELETE, "/admin/employees/${owner.id}/invitation", adminToken(admin))
        // GOV-15 owner 자신도 GOV-03이 상태(ACTIVE라 INVALID_STATE)보다 먼저
        val bySelf = request(HttpMethod.DELETE, "/admin/employees/${owner.id}/invitation", ownerToken(owner))

        listOf(byAdmin, bySelf).forEach {
            assertEquals(403, it.status)
            assertEquals("PROTECTED_ACCOUNT", json(it)["code"])
        }
    }

    @Test
    fun `GOV-02 admin은 PENDING admin의 초대를 취소할 수 없고 owner는 할 수 있음`() {
        val admin = admin()
        val owner = owner()
        val pendingAdmin = employees.create(status = AccountStatus.PENDING, password = null)
        employees.makeAdmin(pendingAdmin)

        val byAdmin = request(HttpMethod.DELETE, "/admin/employees/${pendingAdmin.id}/invitation", adminToken(admin))
        val byOwner = request(HttpMethod.DELETE, "/admin/employees/${pendingAdmin.id}/invitation", ownerToken(owner))

        assertEquals(403, byAdmin.status)
        assertEquals("PROTECTED_ACCOUNT", json(byAdmin)["code"])
        assertEquals(204, byOwner.status)
    }

    private fun admin(): CreatedEmployee = employees.create().also(employees::makeAdmin)

    private fun owner(): CreatedEmployee = employees.create().also(employees::makeOwner)

    private fun adminToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:admin"))

    private fun ownerToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:owner"))

    private fun uniqueKeyword(): String = "kw${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private fun loadProfile(employee: CreatedEmployee) = employees.inTransaction { loadEmployee.findEmployeeById(employee.id) }

    /** DB가 시각을 마이크로초까지 저장하므로 초 단위로 맞춘 현재 시각. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun request(
        method: HttpMethod,
        path: String,
        token: String?,
        body: Map<String, Any?>? = null,
        userAgent: String? = null,
    ): MockHttpServletResponse {
        // 퍼센트 인코딩을 그대로 보내도록 URI 템플릿을 거치지 않습니다
        val builder = MockMvcRequestBuilders.request(method, URI.create(path))
        token?.let { builder.header("Authorization", "Bearer $it") }
        userAgent?.let { builder.header("User-Agent", it) }
        body?.let { builder.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(it)) }
        return mockMvc.perform(builder).andReturn().response
    }

    private fun verifyInvitation(token: String): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/invitations/verify") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("token" to token))
            }.andReturn()
            .response

    @Suppress("UNCHECKED_CAST")
    private fun ids(body: Map<String, Any?>): List<String> = (body["items"] as List<Map<String, Any?>>).map { it["principalId"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        /** api/conventions.md §3 목록 `size` 최대값. */
        const val MAX_PAGE_SIZE = 100
    }
}
