package com.dozycoffee.auth.server.adapter.inbound.web.account

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PasswordCredentialTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.VerificationTable
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
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
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 초대 조회·수락 API (api/account.md, VER-01·VER-04~VER-06, SEC-02, ACC-01). 실제 DB에 커밋하며 확인합니다.
 *
 * 응답 필드 이름, 에러 code, 마스킹 형식은 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
class InvitationApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var clock: Clock

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `SEC-02 초대 조회는 이름, 마스킹한 이메일·전화번호, 만료 시각을 응답`() {
        val employee = invitee(phone = "010-1234-5678")
        val issuedAt = now()
        val token = employees.issueInvitation(employee, issuedAt)

        val response = verify(token)

        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = json(response)
        assertEquals(setOf("name", "maskedEmail", "maskedPhone", "expiresAt"), body.keys)
        assertEquals(employee.name, body["name"])
        assertEquals("em***@dozycoffee.test", body["maskedEmail"])
        assertEquals("010-****-5678", body["maskedPhone"])
        assertEquals(issuedAt.plus(AuthPolicy.INVITATION_TTL), Instant.parse(body["expiresAt"] as String))
    }

    @Test
    fun `전화번호가 없으면 maskedPhone은 null`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        val body = json(verify(token))

        assertTrue(body.containsKey("maskedPhone"))
        assertNull(body["maskedPhone"])
    }

    @Test
    fun `VER-06 초대 조회는 토큰을 소비하지 않아 여러 번 조회하고 수락할 수 있음`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        assertEquals(200, verify(token).status)
        assertEquals(200, verify(token).status)

        assertNull(verificationOf(employee)[VerificationTable.consumedAt])
        assertEquals(204, accept(token, NEW_PASSWORD).status)
    }

    @Test
    fun `VER-04 만료된 초대는 조회와 수락 모두 VERIFICATION_EXPIRED`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now().minus(AuthPolicy.INVITATION_TTL).minus(Duration.ofMinutes(1)))

        assertExpired(verify(token))
        assertExpired(accept(token, NEW_PASSWORD))
        assertNothingChanged(employee)
    }

    @Test
    fun `VER-04 이미 수락한 초대는 조회와 수락 모두 VERIFICATION_EXPIRED`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())
        accept(token, NEW_PASSWORD)

        assertExpired(verify(token))
        assertExpired(accept(token, "another-new-password"))
    }

    @Test
    fun `VER-04 재발송으로 무효화된 초대는 조회와 수락 모두 VERIFICATION_EXPIRED`() {
        val employee = invitee()
        val invalidated = employees.issueInvitation(employee, now())
        val current = employees.issueInvitation(employee, now())

        assertExpired(verify(invalidated))
        assertExpired(accept(invalidated, NEW_PASSWORD))
        assertEquals(200, verify(current).status)
    }

    @Test
    fun `VER-04 없는 토큰은 조회와 수락 모두 VERIFICATION_EXPIRED`() {
        val unknown = "unknown-${UUID.randomUUID()}"

        assertExpired(verify(unknown))
        assertExpired(accept(unknown, NEW_PASSWORD))
    }

    @Test
    fun `초대받은 계정이 PENDING이 아니면 상태를 드러내지 않고 VERIFICATION_EXPIRED`() {
        val active = employees.create(status = AccountStatus.ACTIVE)
        val deactivated = employees.create(status = AccountStatus.DEACTIVATED, password = null)
        val activeToken = employees.issueInvitation(active, now())
        val deactivatedToken = employees.issueInvitation(deactivated, now())

        assertExpired(verify(activeToken))
        assertExpired(accept(activeToken, NEW_PASSWORD))
        assertExpired(verify(deactivatedToken))
        assertExpired(accept(deactivatedToken, NEW_PASSWORD))
        assertNull(verificationOf(active)[VerificationTable.consumedAt])
    }

    @Test
    fun `초대를 수락하면 비밀번호를 만들고 ACTIVE로 바꾸고 초대를 소비함`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        val response = accept(token, NEW_PASSWORD, userAgent = "DozyConsole/1.0")

        assertEquals(204, response.status)
        assertEquals("", response.contentAsString)
        assertNull(response.getHeader("Set-Cookie"))
        assertEquals(AccountStatus.ACTIVE, employees.account(employee.id).status)
        assertEquals(1L, credentialCount(employee))
        assertNotNull(verificationOf(employee)[VerificationTable.consumedAt])
    }

    @Test
    fun `초대를 수락하면 그 직원이 행위자이자 대상인 INVITATION_ACCEPTED를 남김`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        accept(token, NEW_PASSWORD, userAgent = "DozyConsole/1.0")

        val audit =
            employees.inTransaction {
                AuditLogTable.selectAll().where { AuditLogTable.targetId eq employee.id.toString() }.single()
            }
        assertEquals("INVITATION_ACCEPTED", audit[AuditLogTable.action])
        assertEquals(employee.id, audit[AuditLogTable.actorId])
        assertEquals("EMPLOYEE", audit[AuditLogTable.actorType])
        assertEquals("PRINCIPAL", audit[AuditLogTable.targetType])
        assertEquals("DozyConsole/1.0", audit[AuditLogTable.userAgent])
    }

    @Test
    fun `수락한 뒤 새 비밀번호로 로그인할 수 있음`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        accept(token, NEW_PASSWORD)

        assertEquals(200, login(employee.email, NEW_PASSWORD).status)
    }

    @Test
    fun `PWD-01 비밀번호가 너무 짧으면 VALIDATION_FAILED이고 아무것도 바꾸지 않음`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        val response = accept(token, "a".repeat(AuthPolicy.PASSWORD_MIN_LENGTH - 1))

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
        assertNothingChanged(employee)
    }

    @Test
    fun `PWD-03 이메일과 같은 비밀번호는 대소문자가 달라도 VALIDATION_FAILED이고 아무것도 바꾸지 않음`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        val response = accept(token, employee.email.uppercase())

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
        assertNothingChanged(employee)
    }

    @Test
    fun `같은 초대를 동시에 수락하면 하나만 성공하고 나머지는 VERIFICATION_EXPIRED`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())
        val requests = 4
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(requests)

        val statuses =
            try {
                val futures = List(requests) { executor.submit<Int> { start.await().let { accept(token, NEW_PASSWORD).status } } }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertEquals(1, statuses.count { it == 204 }, statuses.toString())
        assertEquals(requests - 1, statuses.count { it == 410 }, statuses.toString())
        assertEquals(1L, credentialCount(employee))
        assertEquals(listOf("INVITATION_ACCEPTED"), employees.auditActions(employee.id))
    }

    @Test
    fun `토큰이 없으면 VALIDATION_FAILED`() {
        val response =
            mockMvc
                .post("/realms/internal/invitations/verify") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andReturn()
                .response

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
    }

    @Test
    fun `VER-05 초대 조회·수락은 인증 없이 호출하며 Authorization 헤더가 있어도 보지 않음`() {
        val employee = invitee()
        val token = employees.issueInvitation(employee, now())

        assertEquals(200, verify(token, authorization = "Bearer not-a-token").status)
        assertEquals(204, accept(token, NEW_PASSWORD, authorization = "Bearer not-a-token").status)
    }

    private fun invitee(phone: String? = null): CreatedEmployee =
        employees.create(status = AccountStatus.PENDING, password = null, phone = phone)

    /** DB가 시각을 마이크로초까지 저장하므로 초 단위로 맞춘 현재 시각. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun verify(
        token: String,
        authorization: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/invitations/verify") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("token" to token))
                authorization?.let { header("Authorization", it) }
            }.andReturn()
            .response

    private fun accept(
        token: String,
        password: String,
        userAgent: String? = null,
        authorization: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/invitations/accept") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("token" to token, "password" to password))
                userAgent?.let { header("User-Agent", it) }
                authorization?.let { header("Authorization", it) }
            }.andReturn()
            .response

    private fun login(
        email: String,
        password: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/login") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))
            }.andReturn()
            .response

    private fun assertExpired(response: MockHttpServletResponse) {
        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
    }

    /** 수락이 거부된 뒤 계정, credential, 초대, 감사 로그가 그대로인지. */
    private fun assertNothingChanged(employee: CreatedEmployee) {
        assertEquals(AccountStatus.PENDING, employees.account(employee.id).status)
        assertEquals(0L, credentialCount(employee))
        assertNull(verificationOf(employee)[VerificationTable.consumedAt])
        assertEquals(emptyList(), employees.auditActions(employee.id))
    }

    private fun credentialCount(employee: CreatedEmployee): Long =
        employees.inTransaction {
            PasswordCredentialTable.selectAll().where { PasswordCredentialTable.principalId eq employee.id }.count()
        }

    /** [employee]의 마지막으로 발급한 초대 행. */
    private fun verificationOf(employee: CreatedEmployee) =
        employees.inTransaction {
            VerificationTable
                .selectAll()
                .where { VerificationTable.principalId eq employee.id }
                .orderBy(VerificationTable.id)
                .last()
        }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        const val NEW_PASSWORD = "new-horse-battery-staple"
    }
}
