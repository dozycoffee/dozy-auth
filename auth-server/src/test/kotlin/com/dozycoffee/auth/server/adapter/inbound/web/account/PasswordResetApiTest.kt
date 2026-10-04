package com.dozycoffee.auth.server.adapter.inbound.web.account

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RefreshSessionTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.VerificationTable
import com.dozycoffee.auth.server.application.port.outbound.mail.PasswordResetMail
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import com.dozycoffee.auth.server.support.RecordingMailConfig
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import com.dozycoffee.auth.server.support.TestEmployees.Companion.WRONG_PASSWORD
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import jakarta.servlet.http.Cookie
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.json.JsonMapper
import java.net.HttpCookie
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 비밀번호 찾기·재설정 API (api/account.md, LGN-04, PWD-01~PWD-03, PWD-07, VER-01, VER-03, VER-04, AUD-08,
 * api/conventions.md §8). 실제 DB에 커밋하며 확인하고, 메일은 커밋 후 발송을 거쳐 [RecordingMailSender]에 기록합니다.
 *
 * 응답 상태, 에러 code, 감사 action은 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, RecordingMailConfig::class)
@ActiveProfiles("test")
class PasswordResetApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var mails: RecordingMailSender

    @Autowired
    lateinit var issueVerification: IssueVerificationPort

    @Autowired
    lateinit var clock: Clock

    @BeforeEach
    fun setUp() = mails.sent.clear()

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `VER-01 ACTIVE 계정이면 202이고 profile 이메일로 재설정 메일을 보냄`() {
        val employee = employees.create()
        val before = clock.instant()

        val response = forgot(employee.email.uppercase())

        assertEquals(202, response.status)
        assertEquals("", response.contentAsString)
        val mail = mails.sent.single() as PasswordResetMail
        assertEquals(Email(employee.email), mail.to)
        assertEquals(Realm.INTERNAL, mail.realm)
        assertTrue(!mail.expiresAt.isBefore(before.plus(AuthPolicy.PASSWORD_RESET_TTL).minusSeconds(1)))
        val verification = verificationRows(employee.id).single()
        assertEquals("PASSWORD_RESET", verification[VerificationTable.purpose])
        assertEquals(employee.email, verification[VerificationTable.target])
    }

    @Test
    fun `LGN-04 없는 이메일도 같은 202이고 메일을 보내지 않음`() {
        val response = forgot("nobody-${UUID.randomUUID()}@dozycoffee.test")

        assertEquals(202, response.status)
        assertEquals("", response.contentAsString)
        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `LGN-04 형식이 틀린 이메일도 같은 202`() {
        val response = forgot("not-an-email")

        assertEquals(202, response.status)
        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `LGN-04 ACTIVE가 아닌 계정은 같은 202이고 메일을 보내지 않음`() {
        val pending = employees.create(status = AccountStatus.PENDING, password = null)
        val suspended = employees.create(status = AccountStatus.SUSPENDED)

        val responses = listOf(forgot(pending.email), forgot(suspended.email))

        assertTrue(responses.all { it.status == 202 })
        assertTrue(mails.sent.isEmpty())
        assertTrue(verificationRows(pending.id).isEmpty())
        assertTrue(verificationRows(suspended.id).isEmpty())
    }

    @Test
    fun `api-conventions 8 같은 이메일 반복 요청은 한도를 넘으면 같은 202이고 메일만 보내지 않음`() {
        val employee = employees.create()
        val limit = AuthPolicy.RATE_LIMIT_EMAIL.capacity

        val responses = List(limit + 1) { forgot(employee.email) }

        assertTrue(responses.all { it.status == 202 })
        assertEquals(limit, mails.sent.size)
    }

    @Test
    fun `VER-03 다시 요청하면 이전 재설정 링크는 쓸 수 없음`() {
        val employee = employees.create()
        val first = requestResetToken(employee)
        val second = requestResetToken(employee)

        val response = reset(first, NEW_PASSWORD)

        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
        assertEquals(204, reset(second, NEW_PASSWORD).status)
    }

    @Test
    fun `PWD-07 재설정하면 이전 비밀번호는 실패하고 새 비밀번호로 로그인`() {
        val employee = employees.create()
        val token = requestResetToken(employee)

        val response = reset(token, NEW_PASSWORD)

        assertEquals(204, response.status)
        assertEquals(401, login(employee.email, PASSWORD).status)
        assertEquals(200, login(employee.email, NEW_PASSWORD).status)
    }

    @Test
    fun `PWD-07 재설정하면 모든 세션을 PASSWORD_RESET으로 폐기`() {
        val employee = employees.create()
        val first = refreshTokenOf(login(employee.email, PASSWORD))
        val second = refreshTokenOf(login(employee.email, PASSWORD))
        val token = requestResetToken(employee)

        reset(token, NEW_PASSWORD)

        assertEquals("SESSION_EXPIRED", json(refresh(first))["code"])
        assertEquals("SESSION_EXPIRED", json(refresh(second))["code"])
        assertEquals(listOf("PASSWORD_RESET", "PASSWORD_RESET"), revokeReasons(employee.id))
    }

    @Test
    fun `PWD-07 로그인 실패로 잠긴 계정도 재설정 후 바로 로그인`() {
        val employee = employees.create()
        repeat(AuthPolicy.LOGIN_LOCK_THRESHOLD) { login(employee.email, WRONG_PASSWORD) }
        assertEquals(429, login(employee.email, PASSWORD).status)
        val token = requestResetToken(employee)

        assertEquals(204, reset(token, NEW_PASSWORD).status)

        val principal = principalRow(employee.id)
        assertNull(principal[PrincipalTable.lockedUntil])
        assertEquals(0, principal[PrincipalTable.failedLoginCount])
        assertEquals(200, login(employee.email, NEW_PASSWORD).status)
    }

    @Test
    fun `PWD-07 재설정하면 잠기기 전의 로그인 실패 횟수도 초기화`() {
        val employee = employees.create()
        repeat(AuthPolicy.LOGIN_LOCK_THRESHOLD - 1) { login(employee.email, WRONG_PASSWORD) }
        val token = requestResetToken(employee)

        reset(token, NEW_PASSWORD)

        assertEquals(0, principalRow(employee.id)[PrincipalTable.failedLoginCount])
    }

    @Test
    fun `AUD-08 PASSWORD_RESET에 행위자·대상은 그 계정이고 함께 폐기한 세션 수를 남김`() {
        val employee = employees.create()
        login(employee.email, PASSWORD)
        login(employee.email, PASSWORD)
        val token = requestResetToken(employee)

        reset(token, NEW_PASSWORD)

        val row =
            employees.inTransaction {
                AuditLogTable
                    .selectAll()
                    .where { AuditLogTable.action eq "PASSWORD_RESET" }
                    .single()
            }
        assertEquals(employee.id, row[AuditLogTable.actorId])
        assertEquals(employee.id.toString(), row[AuditLogTable.targetId])
        assertEquals(mapOf("revokedSessions" to 2), row[AuditLogTable.detail])
    }

    @Test
    fun `AUD-08 비밀번호 찾기 요청은 감사 로그를 남기지 않음`() {
        val employee = employees.create()

        forgot(employee.email)

        assertTrue(employees.auditActions(employee.id).isEmpty())
    }

    @Test
    fun `VER-04 사용한 토큰은 410 VERIFICATION_EXPIRED`() {
        val employee = employees.create()
        val token = requestResetToken(employee)
        assertEquals(204, reset(token, NEW_PASSWORD).status)

        val response = reset(token, "another-horse-battery")

        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
        assertEquals(200, login(employee.email, NEW_PASSWORD).status)
    }

    @Test
    fun `VER-04 만료된 토큰은 410 VERIFICATION_EXPIRED`() {
        val employee = employees.create()
        val token = issueResetToken(employee, clock.instant().minus(AuthPolicy.PASSWORD_RESET_TTL).minusSeconds(1))

        val response = reset(token, NEW_PASSWORD)

        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
    }

    @Test
    fun `VER-04 없는 토큰과 다른 purpose의 토큰은 410 VERIFICATION_EXPIRED`() {
        val employee = employees.create(status = AccountStatus.PENDING, password = null)
        val invitation = employees.issueInvitation(employee, clock.instant())

        val unknown = reset("unknown-token", NEW_PASSWORD)
        val otherPurpose = reset(invitation, NEW_PASSWORD)

        assertEquals("VERIFICATION_EXPIRED", json(unknown)["code"])
        assertEquals(410, otherPurpose.status)
        assertEquals("VERIFICATION_EXPIRED", json(otherPurpose)["code"])
    }

    @Test
    fun `토큰의 계정이 정지됐으면 410 VERIFICATION_EXPIRED이고 비밀번호를 바꾸지 않음`() {
        val employee = employees.create()
        val token = requestResetToken(employee)
        employees.inTransaction {
            PrincipalTable.update({ PrincipalTable.id eq employee.id }) { it[PrincipalTable.status] = AccountStatus.SUSPENDED.name }
        }

        val response = reset(token, NEW_PASSWORD)

        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
        assertTrue("PASSWORD_RESET" !in employees.auditActions(employee.id))
    }

    @Test
    fun `PWD-01 새 비밀번호가 짧으면 400 VALIDATION_FAILED이고 토큰을 소비하지 않아 다시 시도할 수 있음`() {
        val employee = employees.create()
        val token = requestResetToken(employee)

        val response = reset(token, "a".repeat(AuthPolicy.PASSWORD_MIN_LENGTH - 1))

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
        assertNull(verificationRows(employee.id).single()[VerificationTable.consumedAt])
        assertEquals(200, login(employee.email, PASSWORD).status)
        assertEquals(204, reset(token, NEW_PASSWORD).status)
        assertNotNull(verificationRows(employee.id).single()[VerificationTable.consumedAt])
    }

    @Test
    fun `PWD-03 새 비밀번호가 이메일과 같으면 대소문자가 달라도 400 VALIDATION_FAILED`() {
        val employee = employees.create()
        val token = requestResetToken(employee)

        val response = reset(token, employee.email.uppercase())

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
    }

    @Test
    fun `필수 필드가 없으면 400 VALIDATION_FAILED`() {
        val forgot = post(FORGOT_PATH, mapOf<String, String>())
        val reset = post(RESET_PATH, mapOf("token" to "some-token"))

        assertEquals("VALIDATION_FAILED", json(forgot)["code"])
        assertEquals(400, reset.status)
        assertEquals("VALIDATION_FAILED", json(reset)["code"])
    }

    @Test
    fun `아직 제공하지 않는 realm은 404`() {
        val forgot = post("/realms/partner/password/forgot", mapOf("email" to "kim@dozycoffee.test"))
        val reset = post("/realms/partner/password/reset", mapOf("token" to "t", "newPassword" to NEW_PASSWORD))

        assertEquals(404, forgot.status)
        assertEquals(404, reset.status)
    }

    @Test
    fun `api-conventions 8 인증 없는 API라 IP 단위 요청 제한 대상`() {
        val ip = "198.51.100.77"
        val limit = AuthPolicy.RATE_LIMIT_IP.capacity

        val allowed = List(limit) { post(FORGOT_PATH, mapOf("email" to "nobody@dozycoffee.test"), ip) }
        val limitedForgot = post(FORGOT_PATH, mapOf("email" to "nobody@dozycoffee.test"), ip)
        val limitedReset = post(RESET_PATH, mapOf("token" to "t", "newPassword" to NEW_PASSWORD), ip)

        assertTrue(allowed.all { it.status == 202 })
        assertEquals(429, limitedForgot.status)
        assertEquals("TOO_MANY_ATTEMPTS", json(limitedForgot)["code"])
        assertEquals(429, limitedReset.status)
    }

    /** 비밀번호 찾기 API로 메일을 받아 토큰 원문을 꺼냅니다. */
    private fun requestResetToken(employee: CreatedEmployee): String {
        mails.sent.clear()
        check(forgot(employee.email).status == 202)
        return (mails.sent.single() as PasswordResetMail).token.value
    }

    /** [issuedAt]에 발급한 `PASSWORD_RESET`을 저장하고 토큰 원문을 돌려줍니다. */
    private fun issueResetToken(
        employee: CreatedEmployee,
        issuedAt: Instant,
    ): String =
        employees.inTransaction {
            val issued = NewVerification.issue(employee.id, VerificationPurpose.PASSWORD_RESET, Email(employee.email), issuedAt)
            issueVerification.issue(issued.verification)
            issued.token.value
        }

    private fun forgot(email: String): MockHttpServletResponse = post(FORGOT_PATH, mapOf("email" to email))

    private fun reset(
        token: String,
        newPassword: String,
    ): MockHttpServletResponse = post(RESET_PATH, mapOf("token" to token, "newPassword" to newPassword))

    private fun post(
        path: String,
        body: Map<String, String>,
        ip: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(body)
                ip?.let { with(remoteAddr(it)) }
            }.andReturn()
            .response

    private fun login(
        email: String,
        password: String,
    ): MockHttpServletResponse = post("/realms/internal/login", mapOf("email" to email, "password" to password))

    private fun refreshTokenOf(response: MockHttpServletResponse): String {
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

    private fun remoteAddr(ip: String) =
        RequestPostProcessor { request ->
            request.remoteAddr = ip
            request
        }

    private fun verificationRows(principalId: UUID) =
        employees.inTransaction {
            VerificationTable
                .selectAll()
                .where { VerificationTable.principalId eq principalId }
                .orderBy(VerificationTable.id)
                .toList()
        }

    private fun revokeReasons(principalId: UUID): List<String?> =
        employees.inTransaction {
            RefreshSessionTable
                .selectAll()
                .where { RefreshSessionTable.principalId eq principalId }
                .map { it[RefreshSessionTable.revokeReason] }
        }

    private fun principalRow(principalId: UUID) =
        employees.inTransaction { PrincipalTable.selectAll().where { PrincipalTable.id eq principalId }.single() }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        const val FORGOT_PATH = "/realms/internal/password/forgot"
        const val RESET_PATH = "/realms/internal/password/reset"
        const val NEW_PASSWORD = "new-horse-battery-staple"

        /** test 프로필의 CORS 허용 origin (`application-test.yaml`). */
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
    }
}
