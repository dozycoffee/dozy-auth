package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.RefreshSessionTable
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.support.MutableClock
import com.dozycoffee.auth.server.support.MutableClockConfiguration
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.Cookie
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 토큰 갱신 API (api/auth.md 토큰 갱신, SES-03~SES-05, AUD-08, api/conventions.md §6~§8). 실제 DB에 커밋하며 확인합니다.
 *
 * 서버의 `Clock`을 [MutableClock]으로 바꿔 유예 시간과 만료를 기다리지 않고 넘깁니다. 시계는 테스트끼리 공유하므로 각 테스트는
 * 지금 시각에서 상대적으로 옮깁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
class RefreshTokenApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var clock: MutableClock

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `SES-03 현재 토큰이면 새 access token과 교체한 refresh 쿠키를 받음`() {
        val employee = employees.create()
        val loginToken = login(employee.email)

        val response = refresh(loginToken)

        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        val body = json(response)
        assertEquals("Bearer", body["tokenType"])
        assertEquals(AuthPolicy.ACCESS_TOKEN_TTL.seconds, (body["expiresIn"] as Number).toLong())
        val header = checkNotNull(response.getHeader("Set-Cookie"))
        val cookie = HttpCookie.parse(header).single()
        assertEquals("dozy_refresh", cookie.name)
        assertNotEquals(loginToken, cookie.value)
        assertEquals("/realms/internal", cookie.path)
        assertEquals(AuthPolicy.REFRESH_ABSOLUTE_TTL.seconds, cookie.maxAge)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.secure)
        assertNull(cookie.domain)
        assertTrue(header.contains("SameSite=Strict"), header)
    }

    @Test
    fun `SES-01 갱신하면 같은 세션 행의 해시를 교체하고 access token의 sid는 그 세션`() {
        val employee = employees.create()
        val loginToken = login(employee.email)

        val response = refresh(loginToken)

        val session = sessionOf(employee.id)
        assertEquals(SecretHash.of(refreshCookie(response)).hex, session[RefreshSessionTable.currentTokenHash])
        assertEquals(SecretHash.of(loginToken).hex, session[RefreshSessionTable.previousTokenHash])
        val sid = SignedJWT.parse(json(response)["accessToken"] as String).jwtClaimsSet.getStringClaim("sid")
        assertEquals(session[RefreshSessionTable.id].toString(), sid)
    }

    @Test
    fun `쿠키 Max-Age는 갱신해도 절대 만료까지 남은 시간`() {
        val employee = employees.create()
        val loginToken = login(employee.email)
        clock.advance(Duration.ofHours(1))

        val cookie = HttpCookie.parse(refresh(loginToken).getHeader("Set-Cookie")).single()

        assertEquals(AuthPolicy.REFRESH_ABSOLUTE_TTL.minusHours(1).seconds, cookie.maxAge)
    }

    @Test
    fun `SES-05 갱신한 토큰에는 갱신 시점의 role을 담음`() {
        val role = employees.newRoleCode("wms")
        val employee = employees.create(roles = listOf(role))
        val loginToken = login(employee.email)

        val response = refresh(loginToken)

        val roles = SignedJWT.parse(json(response)["accessToken"] as String).jwtClaimsSet.getStringListClaim("roles")
        assertEquals(listOf(role.value), roles)
    }

    @Test
    fun `SES-03 교체 직후 직전 토큰이면 409 TOKEN_ROTATED이고 세션은 유지`() {
        val employee = employees.create()
        val loginToken = login(employee.email)
        val rotated = refreshCookie(refresh(loginToken))
        clock.advance(AuthPolicy.ROTATION_GRACE)

        val response = refresh(loginToken)

        assertEquals(409, response.status)
        assertEquals("TOKEN_ROTATED", json(response)["code"])
        assertNull(response.getHeader("Set-Cookie"))
        assertNull(sessionOf(employee.id)[RefreshSessionTable.revokedAt])
        assertEquals(200, refresh(rotated).status)
    }

    @Test
    fun `SES-03 유예 시간이 지난 직전 토큰이면 세션을 폐기하고 401 SESSION_REVOKED`() {
        val employee = employees.create()
        val loginToken = login(employee.email)
        val rotated = refreshCookie(refresh(loginToken))
        clock.advance(AuthPolicy.ROTATION_GRACE.plusSeconds(1))

        val response = refresh(loginToken, userAgent = "Unknown/1.0")

        assertEquals(401, response.status)
        assertEquals("SESSION_REVOKED", json(response)["code"])
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        assertNull(response.getHeader("Set-Cookie"))
        // 에러 응답이어도 폐기와 감사 기록은 커밋됨 (architecture.md §9.2)
        val session = sessionOf(employee.id)
        assertNotNull(session[RefreshSessionTable.revokedAt])
        assertEquals("REUSE_DETECTED", session[RefreshSessionTable.revokeReason])
        assertEquals(listOf("LOGIN_SUCCEEDED", "SESSION_REVOKED"), employees.auditActions(employee.id))
        val detail = checkNotNull(employees.auditDetails(employee.id).last())
        assertEquals(
            mapOf("realm" to "internal", "sessionId" to session[RefreshSessionTable.id].toString(), "reason" to "REUSE_DETECTED"),
            detail,
        )
        // 폐기된 세션은 현재 토큰도 쓸 수 없음
        assertEquals("SESSION_EXPIRED", json(refresh(rotated))["code"])
    }

    @Test
    fun `SES-03 모르는 토큰이나 쿠키가 없으면 401 SESSION_EXPIRED`() {
        val unknown = refresh("unknown-${UUID.randomUUID()}")
        val missing = refresh(null)

        assertEquals(401, unknown.status)
        assertEquals("SESSION_EXPIRED", json(unknown)["code"])
        assertEquals(401, missing.status)
        assertEquals("SESSION_EXPIRED", json(missing)["code"])
    }

    @Test
    fun `SES-03 유휴 만료가 지난 세션이면 401 SESSION_EXPIRED`() {
        val employee = employees.create()
        val loginToken = login(employee.email)
        clock.advance(AuthPolicy.REFRESH_IDLE_TTL)

        val response = refresh(loginToken)

        assertEquals(401, response.status)
        assertEquals("SESSION_EXPIRED", json(response)["code"])
    }

    @Test
    fun `SES-05 로그인 뒤 계정이 정지되면 401 SESSION_EXPIRED이고 세션은 교체하지 않음`() {
        val employee = employees.create()
        val loginToken = login(employee.email)
        employees.inTransaction { PrincipalTable.update({ PrincipalTable.id eq employee.id }) { it[PrincipalTable.status] = "SUSPENDED" } }

        val response = refresh(loginToken)

        assertEquals(401, response.status)
        assertEquals("SESSION_EXPIRED", json(response)["code"])
        assertEquals(SecretHash.of(loginToken).hex, sessionOf(employee.id)[RefreshSessionTable.currentTokenHash])
    }

    @Test
    fun `쿠키의 세션과 다른 realm 경로로 갱신하면 401 SESSION_EXPIRED`() {
        val employee = employees.create()
        val loginToken = login(employee.email)

        val response = refresh(loginToken, realm = "partner")

        assertEquals(401, response.status)
        assertEquals("SESSION_EXPIRED", json(response)["code"])
        assertEquals(SecretHash.of(loginToken).hex, sessionOf(employee.id)[RefreshSessionTable.currentTokenHash])
    }

    @Test
    fun `받지 않는 realm은 404`() {
        assertEquals(404, refresh("any", realm = "customer").status)
    }

    @Test
    fun `Origin이 없거나 허용 목록에 없으면 403 FORBIDDEN이고 세션을 건드리지 않음`() {
        val employee = employees.create()
        val loginToken = login(employee.email)

        val missing = refresh(loginToken, origin = null)
        val foreign = refresh(loginToken, origin = "https://evil.example")

        for (response in listOf(missing, foreign)) {
            assertEquals(403, response.status)
            assertEquals("application/problem+json", response.contentType)
            assertEquals("FORBIDDEN", json(response)["code"])
            assertNull(response.getHeader("Set-Cookie"))
        }
        assertEquals(SecretHash.of(loginToken).hex, sessionOf(employee.id)[RefreshSessionTable.currentTokenHash])
    }

    @Test
    fun `허용 origin의 갱신 응답에는 CORS 헤더가 있음`() {
        val employee = employees.create()

        val response = refresh(login(employee.email))

        assertEquals(ALLOWED_ORIGIN, response.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
    }

    @Test
    fun `토큰 갱신은 IP 단위 요청 제한 대상이 아님`() {
        val responses = List(AuthPolicy.RATE_LIMIT_IP.capacity + 1) { refresh("unknown-${UUID.randomUUID()}") }

        assertTrue(responses.all { it.status == 401 }, responses.map { it.status }.toString())
    }

    @Test
    fun `SES-04 같은 토큰으로 동시에 갱신하면 하나만 교체하고 나머지는 409 TOKEN_ROTATED`() {
        val employee = employees.create()
        val loginToken = login(employee.email)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)

        val statuses =
            try {
                List(CONCURRENT_REQUESTS) { executor.submit<Int> { start.await().let { refresh(loginToken).status } } }
                    .also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertEquals(listOf(200) + List(CONCURRENT_REQUESTS - 1) { 409 }, statuses.sorted())
        assertEquals(SecretHash.of(loginToken).hex, sessionOf(employee.id)[RefreshSessionTable.previousTokenHash])
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
        return refreshCookie(response)
    }

    private fun refresh(
        token: String?,
        realm: String = "internal",
        origin: String? = ALLOWED_ORIGIN,
        userAgent: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/$realm/token/refresh") {
                token?.let { cookie(Cookie("dozy_refresh", it)) }
                origin?.let { header("Origin", it) }
                userAgent?.let { header("User-Agent", it) }
            }.andReturn()
            .response

    private fun refreshCookie(response: MockHttpServletResponse): String =
        HttpCookie.parse(checkNotNull(response.getHeader("Set-Cookie"))).single().value

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
        const val CONCURRENT_REQUESTS = 2
    }
}
