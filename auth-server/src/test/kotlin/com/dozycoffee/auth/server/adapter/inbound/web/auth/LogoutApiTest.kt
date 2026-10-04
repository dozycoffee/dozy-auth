package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RefreshSessionTable
import com.dozycoffee.auth.server.support.MutableClockConfiguration
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import jakarta.servlet.http.Cookie
import org.jetbrains.exposed.v1.core.ResultRow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 로그아웃 API (api/auth.md 로그아웃, SES-06, SES-08, AUD-08, api/conventions.md §6·§7). 실제 DB에 커밋하며 확인합니다.
 *
 * [RefreshTokenApiTest]와 같은 설정이라 스프링 컨텍스트를 함께 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
class LogoutApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `SES-06 로그아웃하면 세션을 LOGOUT으로 폐기하고 SESSION_REVOKED를 남김`() {
        val employee = employees.create()
        val token = login(employee.email)

        val response = logout(token, userAgent = "DozyConsole/1.0")

        assertEquals(204, response.status)
        val session = sessionOf(employee.id)
        assertNotNull(session[RefreshSessionTable.revokedAt])
        assertEquals("LOGOUT", session[RefreshSessionTable.revokeReason])
        assertEquals(listOf("LOGIN_SUCCEEDED", "SESSION_REVOKED"), employees.auditActions(employee.id))
        assertEquals(
            mapOf("realm" to "internal", "sessionId" to session[RefreshSessionTable.id].toString(), "reason" to "LOGOUT"),
            employees.auditDetails(employee.id).last(),
        )
        assertEquals("SESSION_EXPIRED", json(refresh(token))["code"])
    }

    @Test
    fun `로그아웃 응답은 같은 속성에 빈 값과 Max-Age=0으로 쿠키를 삭제`() {
        val employee = employees.create()

        val header = checkNotNull(logout(login(employee.email)).getHeader("Set-Cookie"))

        val cookie = HttpCookie.parse(header).single()
        assertEquals("dozy_refresh", cookie.name)
        assertEquals("", cookie.value)
        assertEquals(0, cookie.maxAge)
        assertEquals("/realms/internal", cookie.path)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.secure)
        assertNull(cookie.domain)
        assertTrue(header.contains("SameSite=Strict"), header)
    }

    @Test
    fun `SES-08 쿠키가 없거나 모르는 토큰이어도 204로 쿠키를 삭제`() {
        for (response in listOf(logout(null), logout("unknown-${UUID.randomUUID()}"))) {
            assertEquals(204, response.status)
            assertEquals(0, HttpCookie.parse(response.getHeader("Set-Cookie")).single().maxAge)
        }
    }

    @Test
    fun `SES-08 이미 로그아웃한 세션이면 204이고 다시 기록하지 않음`() {
        val employee = employees.create()
        val token = login(employee.email)
        logout(token)

        val response = logout(token)

        assertEquals(204, response.status)
        assertEquals(listOf("LOGIN_SUCCEEDED", "SESSION_REVOKED"), employees.auditActions(employee.id))
    }

    @Test
    fun `Origin이 없거나 허용 목록에 없으면 403 FORBIDDEN이고 세션을 폐기하지 않음`() {
        val employee = employees.create()
        val token = login(employee.email)

        val missing = logout(token, origin = null)
        val foreign = logout(token, origin = "https://evil.example")

        for (response in listOf(missing, foreign)) {
            assertEquals(403, response.status)
            assertEquals("FORBIDDEN", json(response)["code"])
            assertNull(response.getHeader("Set-Cookie"))
        }
        assertNull(sessionOf(employee.id)[RefreshSessionTable.revokedAt])
    }

    private fun login(email: String): String {
        val response =
            mockMvc
                .post("/realms/internal/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to PASSWORD))
                }.andReturn()
                .response
        check(response.status == 200) { "로그인 실패: ${response.status}" }
        return HttpCookie.parse(checkNotNull(response.getHeader("Set-Cookie"))).single().value
    }

    private fun logout(
        token: String?,
        origin: String? = ALLOWED_ORIGIN,
        userAgent: String? = null,
    ): MockHttpServletResponse = post("logout", token, origin, userAgent)

    private fun refresh(token: String): MockHttpServletResponse = post("token/refresh", token, ALLOWED_ORIGIN, null)

    private fun post(
        path: String,
        token: String?,
        origin: String?,
        userAgent: String?,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/$path") {
                token?.let { cookie(Cookie("dozy_refresh", it)) }
                origin?.let { header("Origin", it) }
                userAgent?.let { header("User-Agent", it) }
            }.andReturn()
            .response

    private fun sessionOf(principalId: UUID): ResultRow =
        employees.inTransaction {
            RefreshSessionTable.selectAll().where { RefreshSessionTable.principalId eq principalId }.single()
        }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private companion object {
        /** test 프로필의 CORS 허용 origin (`application-test.yaml`). */
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
    }
}
