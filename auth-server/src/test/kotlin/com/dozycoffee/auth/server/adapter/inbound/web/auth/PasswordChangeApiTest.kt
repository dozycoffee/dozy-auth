package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PasswordCredentialTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RefreshSessionTable
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import com.dozycoffee.auth.server.support.TestEmployees.Companion.WRONG_PASSWORD
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import jakarta.servlet.http.Cookie
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
import java.net.HttpCookie
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 비밀번호 변경 API (api/auth.md 비밀번호 변경, PWD-01~PWD-03, PWD-06, PWD-08, AUD-08, api/conventions.md §8).
 * 실제 DB에 커밋하며 확인합니다. 응답의 에러 code와 감사 action은 명세의 문자열 그대로 기대값으로 씁니다.
 *
 * [MeApiTest]와 같은 설정이라 스프링 컨텍스트를 함께 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class)
@ActiveProfiles("test")
class PasswordChangeApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `PWD-06 바꾸면 현재 세션은 유지하고 다른 세션은 PASSWORD_CHANGED로 폐기`() {
        val employee = employees.create()
        val current = login(employee.email)
        val other = login(employee.email)

        val response = change(current.accessToken, PASSWORD, NEW_PASSWORD)

        assertEquals(204, response.status)
        assertEquals("SESSION_EXPIRED", json(refresh(other.refreshToken))["code"])
        assertEquals(200, refresh(current.refreshToken).status)
        val revoked = sessions(employee.id).filter { it.second != null }
        assertEquals(listOf("PASSWORD_CHANGED"), revoked.map { it.second })
    }

    @Test
    fun `PWD-06 바꾼 뒤에는 새 비밀번호로만 로그인`() {
        val employee = employees.create()

        change(login(employee.email).accessToken, PASSWORD, NEW_PASSWORD)

        assertEquals(401, loginResponse(employee.email, PASSWORD).status)
        assertEquals(200, loginResponse(employee.email, NEW_PASSWORD).status)
    }

    @Test
    fun `AUD-08 PASSWORD_CHANGED에 함께 폐기한 세션 수를 남김`() {
        val employee = employees.create()
        val current = login(employee.email)
        login(employee.email)
        login(employee.email)

        change(current.accessToken, PASSWORD, NEW_PASSWORD, userAgent = "DozyConsole/1.0")

        assertEquals("PASSWORD_CHANGED", employees.auditActions(employee.id).last())
        assertEquals(mapOf("revokedSessions" to 2), employees.auditDetails(employee.id).last())
    }

    @Test
    fun `토큰에 sid가 없으면 모든 세션을 폐기`() {
        val employee = employees.create()
        val session = login(employee.email)

        val response = change(tokens.issue(employee.key, sessionId = null), PASSWORD, NEW_PASSWORD)

        assertEquals(204, response.status)
        assertEquals("SESSION_EXPIRED", json(refresh(session.refreshToken))["code"])
    }

    @Test
    fun `PWD-08 현재 비밀번호가 틀리면 401이 아니라 400 CURRENT_PASSWORD_MISMATCH이고 아무것도 바꾸지 않음`() {
        val employee = employees.create()
        val current = login(employee.email)
        val other = login(employee.email)

        val response = change(current.accessToken, WRONG_PASSWORD, NEW_PASSWORD)

        assertEquals(400, response.status)
        assertEquals("CURRENT_PASSWORD_MISMATCH", json(response)["code"])
        assertEquals(200, refresh(other.refreshToken).status)
        assertEquals(200, loginResponse(employee.email, PASSWORD).status)
        assertTrue("PASSWORD_CHANGED" !in employees.auditActions(employee.id))
    }

    @Test
    fun `PWD-01 새 비밀번호가 최소 길이보다 짧으면 400 VALIDATION_FAILED`() {
        val employee = employees.create()

        val response = change(login(employee.email).accessToken, PASSWORD, "a".repeat(AuthPolicy.PASSWORD_MIN_LENGTH - 1))

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
        assertEquals(200, loginResponse(employee.email, PASSWORD).status)
    }

    @Test
    fun `PWD-03 새 비밀번호가 이메일과 같으면 대소문자가 달라도 400 VALIDATION_FAILED`() {
        val employee = employees.create()

        val response = change(login(employee.email).accessToken, PASSWORD, employee.email.uppercase())

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
    }

    @Test
    fun `필수 필드가 없으면 400 VALIDATION_FAILED`() {
        val employee = employees.create()

        val response = post(tokens.issue(employee.key), mapOf("currentPassword" to PASSWORD))

        assertEquals(400, response.status)
        assertEquals("VALIDATION_FAILED", json(response)["code"])
    }

    @Test
    fun `본인 확인 한도를 넘으면 맞는 비밀번호여도 429 TOO_MANY_ATTEMPTS와 Retry-After`() {
        val employee = employees.create()
        val accessToken = login(employee.email).accessToken
        val limit = AuthPolicy.RATE_LIMIT_PASSWORD_CONFIRM

        val attempts = List(limit.capacity) { change(accessToken, WRONG_PASSWORD, NEW_PASSWORD) }
        val limited = change(accessToken, PASSWORD, NEW_PASSWORD)

        assertTrue(attempts.all { it.status == 400 })
        assertEquals(429, limited.status)
        assertEquals("TOO_MANY_ATTEMPTS", json(limited)["code"])
        val perAttempt = limit.period.dividedBy(limit.capacity.toLong())
        assertEquals(perAttempt.seconds.toString(), limited.getHeader("Retry-After"))
        assertEquals(200, loginResponse(employee.email, PASSWORD).status)
    }

    @Test
    fun `성공한 변경도 본인 확인 한도에 셈`() {
        val employee = employees.create()
        val accessToken = login(employee.email).accessToken
        val passwords = List(AuthPolicy.RATE_LIMIT_PASSWORD_CONFIRM.capacity + 1) { "new-password-$it" }

        val responses = (listOf(PASSWORD) + passwords).zipWithNext { old, new -> change(accessToken, old, new) }

        assertTrue(responses.dropLast(1).all { it.status == 204 })
        assertEquals(429, responses.last().status)
    }

    @Test
    fun `본인 확인 한도는 principal마다 따로 셈`() {
        val limited = employees.create()
        val another = employees.create()
        val limitedToken = login(limited.email).accessToken
        repeat(AuthPolicy.RATE_LIMIT_PASSWORD_CONFIRM.capacity) { change(limitedToken, WRONG_PASSWORD, NEW_PASSWORD) }

        val response = change(login(another.email).accessToken, PASSWORD, NEW_PASSWORD)

        assertEquals(204, response.status)
    }

    @Test
    fun `system token은 403 FORBIDDEN`() {
        val response = change(tokens.issue(SYSTEM, roles = listOf("auth:partner_reader")), PASSWORD, NEW_PASSWORD)

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        val response =
            mockMvc
                .post("/realms/internal/password/change") {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(mapOf("currentPassword" to PASSWORD, "newPassword" to NEW_PASSWORD))
                }.andReturn()
                .response

        assertEquals(401, response.status)
        assertEquals("UNAUTHENTICATED", json(response)["code"])
    }

    @Test
    fun `비활성화된 계정이면 401 UNAUTHENTICATED`() {
        val employee = employees.create(status = AccountStatus.DEACTIVATED)

        val response = change(tokens.issue(employee.key), PASSWORD, NEW_PASSWORD)

        assertEquals(401, response.status)
        assertEquals("UNAUTHENTICATED", json(response)["code"])
    }

    @Test
    fun `ACC-01 정지된 계정이면 409 INVALID_STATE이고 비밀번호를 바꾸지 않음`() {
        val employee = employees.create(status = AccountStatus.SUSPENDED)
        val hashBefore = passwordHash(employee.id)

        val response = change(tokens.issue(employee.key), PASSWORD, NEW_PASSWORD)

        assertEquals(409, response.status)
        assertEquals("INVALID_STATE", json(response)["code"])
        assertEquals(hashBefore, passwordHash(employee.id))
    }

    private data class Session(
        val accessToken: String,
        val refreshToken: String,
    )

    private fun login(email: String): Session {
        val response = loginResponse(email, PASSWORD)
        check(response.status == 200) { "로그인 실패: ${response.status}" }
        return Session(
            accessToken = json(response)["accessToken"] as String,
            refreshToken = HttpCookie.parse(checkNotNull(response.getHeader("Set-Cookie"))).single().value,
        )
    }

    private fun loginResponse(
        email: String,
        password: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/login") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))
            }.andReturn()
            .response

    private fun change(
        accessToken: String,
        currentPassword: String,
        newPassword: String,
        userAgent: String? = null,
    ): MockHttpServletResponse = post(accessToken, mapOf("currentPassword" to currentPassword, "newPassword" to newPassword), userAgent)

    private fun post(
        accessToken: String,
        body: Map<String, String>,
        userAgent: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/password/change") {
                header("Authorization", "Bearer $accessToken")
                userAgent?.let { header("User-Agent", it) }
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(body)
            }.andReturn()
            .response

    private fun refresh(refreshToken: String): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/token/refresh") {
                cookie(Cookie("dozy_refresh", refreshToken))
                header("Origin", ALLOWED_ORIGIN)
            }.andReturn()
            .response

    /** 세션별 (id, 폐기 사유). */
    private fun sessions(principalId: UUID): List<Pair<UUID, String?>> =
        employees.inTransaction {
            RefreshSessionTable
                .selectAll()
                .where { RefreshSessionTable.principalId eq principalId }
                .map { it[RefreshSessionTable.id] to it[RefreshSessionTable.revokeReason] }
        }

    private fun passwordHash(principalId: UUID): String =
        employees.inTransaction {
            PasswordCredentialTable
                .selectAll()
                .where { PasswordCredentialTable.principalId eq principalId }
                .single()[PasswordCredentialTable.passwordHash]
        }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        const val NEW_PASSWORD = "new-horse-battery-staple"

        /** test 프로필의 CORS 허용 origin (`application-test.yaml`). */
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
    }
}
