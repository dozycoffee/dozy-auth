package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.RefreshSessionTable
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import com.dozycoffee.auth.server.support.TestEmployees.Companion.WRONG_PASSWORD
import com.dozycoffee.auth.starter.DozyAuthProperties
import com.dozycoffee.auth.starter.DozyJwtDecoders
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.HttpCookie
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 로그인 API (api/auth.md 로그인, LGN-01~LGN-03, SES-01, AUD-08). 실제 DB에 커밋하며 확인합니다.
 *
 * 응답의 필드 이름, 쿠키 이름·속성, 에러 code는 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
class LoginApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `로그인하면 access token과 refresh 쿠키를 받음`() {
        val employee = employees.create()

        val response = login(employee.email, PASSWORD, userAgent = "DozyConsole/1.0")

        assertEquals(200, response.status)
        val body = json(response)
        assertEquals("Bearer", body["tokenType"])
        assertEquals(AuthPolicy.ACCESS_TOKEN_TTL.seconds, (body["expiresIn"] as Number).toLong())
        assertTrue((body["accessToken"] as String).isNotBlank())
        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    @Test
    fun `refresh 쿠키는 HttpOnly, Secure, SameSite=Strict, realm 경로이고 Max-Age는 남은 절대 만료 시간`() {
        val employee = employees.create()

        val header = checkNotNull(login(employee.email, PASSWORD).getHeader("Set-Cookie"))

        val cookie = HttpCookie.parse(header).single()
        assertEquals("dozy_refresh", cookie.name)
        assertTrue(cookie.value.isNotBlank())
        assertEquals("/realms/internal", cookie.path)
        assertEquals(AuthPolicy.REFRESH_ABSOLUTE_TTL.seconds, cookie.maxAge)
        assertTrue(cookie.isHttpOnly)
        assertTrue(cookie.secure)
        assertNull(cookie.domain)
        assertTrue(header.contains("SameSite=Strict"), header)
    }

    @Test
    fun `SES-01 로그인하면 refresh 세션이 한 행 생기고 토큰 해시, IP, User-Agent를 남김`() {
        val employee = employees.create()

        val response = login(employee.email, PASSWORD, userAgent = "DozyConsole/1.0")

        val refreshToken = HttpCookie.parse(response.getHeader("Set-Cookie")).single().value
        val sessions =
            employees.inTransaction {
                RefreshSessionTable.selectAll().where { RefreshSessionTable.principalId eq employee.id }.toList()
            }
        val session = sessions.single()
        assertEquals(SecretHash.of(refreshToken).hex, session[RefreshSessionTable.currentTokenHash])
        assertEquals("INTERNAL", session[RefreshSessionTable.realm])
        assertEquals("DozyConsole/1.0", session[RefreshSessionTable.userAgent])
        assertEquals("127.0.0.1", session[RefreshSessionTable.ip])
    }

    @Test
    fun `발급한 토큰은 스타터 검증기를 통과하고 role과 aud, 세션 id를 담음`() {
        val wmsRole = employees.newRoleCode("wms")
        val employee = employees.create(roles = listOf(wmsRole))

        val accessToken = json(login(employee.email, PASSWORD))["accessToken"] as String

        // WMS 서비스 입장에서 JWKS API로 받은 공개키로 검증 (token.md §6)
        val jwt = serviceDecoder(audience = "wms").decode(accessToken)
        assertEquals("http://localhost:8080/realms/internal", jwt.issuer.toString())
        assertEquals("employee:${employee.id}", jwt.subject)
        assertEquals(listOf("wms"), jwt.audience)
        assertEquals(listOf(wmsRole.value), jwt.getClaimAsStringList("roles"))
        val sessionId =
            employees.inTransaction {
                RefreshSessionTable.selectAll().where { RefreshSessionTable.principalId eq employee.id }.single()[RefreshSessionTable.id]
            }
        assertEquals(sessionId.toString(), jwt.getClaimAsString("sid"))
    }

    @Test
    fun `LGN-01 성공하면 실패 횟수를 초기화하고 LOGIN_SUCCEEDED를 남김`() {
        val employee = employees.create()
        login(employee.email, WRONG_PASSWORD)

        login(employee.email, PASSWORD)

        assertEquals(0, employees.account(employee.id).failedLoginCount)
        assertEquals(listOf("LOGIN_FAILED", "LOGIN_SUCCEEDED"), employees.auditActions(employee.id))
        val detail = checkNotNull(employees.auditDetails(employee.id).last())
        assertEquals("internal", detail["realm"])
        assertNotNull(detail["sessionId"])
    }

    @Test
    fun `LGN-02 없는 계정과 틀린 비밀번호는 같은 응답`() {
        val employee = employees.create()

        val unknown = login("nobody-${UUID.randomUUID()}@dozycoffee.test", PASSWORD)
        val wrongPassword = login(employee.email, WRONG_PASSWORD)

        assertEquals(401, unknown.status)
        assertEquals(unknown.status, wrongPassword.status)
        assertEquals("Bearer", wrongPassword.getHeader("WWW-Authenticate"))
        assertEquals(unknown.getHeader("WWW-Authenticate"), wrongPassword.getHeader("WWW-Authenticate"))
        assertEquals("INVALID_CREDENTIALS", json(unknown)["code"])
        assertEquals(json(unknown) - "traceId", json(wrongPassword) - "traceId")
        assertNull(wrongPassword.getHeader("Set-Cookie"))
    }

    @Test
    fun `AUD-08 없는 계정의 로그인 실패는 행위자와 대상 없이 realm만 남김`() {
        val before = unknownAccountFailures()

        login("nobody-${UUID.randomUUID()}@dozycoffee.test", PASSWORD)

        assertEquals(before + 1, unknownAccountFailures())
    }

    @Test
    fun `LGN-02 이메일 형식이 아니어도 없는 계정과 같은 응답`() {
        val response = login("not-an-email", PASSWORD)

        assertEquals(401, response.status)
        assertEquals("INVALID_CREDENTIALS", json(response)["code"])
    }

    @Test
    fun `LGN-01 틀린 비밀번호는 에러 응답이어도 실패 횟수와 감사 기록이 커밋됨`() {
        val employee = employees.create()

        login(employee.email, WRONG_PASSWORD)

        assertEquals(1, employees.account(employee.id).failedLoginCount)
        assertEquals(listOf("LOGIN_FAILED"), employees.auditActions(employee.id))
        assertEquals("INVALID_CREDENTIALS", employees.auditDetails(employee.id).single()?.get("reason"))
    }

    @Test
    fun `LGN-01 기준 횟수만큼 틀리면 잠그고 실패 횟수를 0으로 되돌리며 ACCOUNT_LOCKED를 남김`() {
        val employee = employees.create()

        val responses = List(AuthPolicy.LOGIN_LOCK_THRESHOLD) { login(employee.email, WRONG_PASSWORD) }

        assertTrue(responses.all { it.status == 401 })
        val account = employees.account(employee.id)
        assertEquals(0, account.failedLoginCount)
        assertNotNull(account.lockedUntil)
        assertEquals(
            List(AuthPolicy.LOGIN_LOCK_THRESHOLD) { "LOGIN_FAILED" } + "ACCOUNT_LOCKED",
            employees.auditActions(employee.id),
        )
    }

    @Test
    fun `LGN-01 잠긴 계정은 비밀번호가 맞아도 TOO_MANY_ATTEMPTS와 Retry-After`() {
        val employee = employees.create()
        repeat(AuthPolicy.LOGIN_LOCK_THRESHOLD) { login(employee.email, WRONG_PASSWORD) }
        val auditCount = employees.auditActions(employee.id).size

        val response = login(employee.email, PASSWORD)

        assertEquals(429, response.status)
        assertEquals("TOO_MANY_ATTEMPTS", json(response)["code"])
        val retryAfter = checkNotNull(response.getHeader("Retry-After")).toLong()
        assertTrue(retryAfter in 1..AuthPolicy.LOGIN_LOCK_DURATION.seconds, "Retry-After: $retryAfter")
        assertNull(response.getHeader("Set-Cookie"))
        assertEquals(auditCount, employees.auditActions(employee.id).size)
    }

    @Test
    fun `LGN-03 초대 수락 전 직원은 비밀번호가 없어 INVALID_CREDENTIALS`() {
        val employee = employees.create(status = AccountStatus.PENDING, password = null)

        val response = login(employee.email, PASSWORD)

        assertEquals(401, response.status)
        assertEquals("INVALID_CREDENTIALS", json(response)["code"])
    }

    @Test
    fun `LGN-03 비밀번호가 맞은 PENDING 계정은 EMAIL_NOT_VERIFIED`() {
        val employee = employees.create(status = AccountStatus.PENDING)

        val response = login(employee.email, PASSWORD)

        assertEquals(403, response.status)
        assertEquals("EMAIL_NOT_VERIFIED", json(response)["code"])
    }

    @Test
    fun `LGN-03 비밀번호가 맞은 정지 계정은 ACCOUNT_SUSPENDED이고 실패 횟수는 늘리지 않고 LOGIN_FAILED를 남김`() {
        val employee = employees.create(status = AccountStatus.SUSPENDED)

        val response = login(employee.email, PASSWORD)

        assertEquals(403, response.status)
        assertEquals("ACCOUNT_SUSPENDED", json(response)["code"])
        assertNull(response.getHeader("Set-Cookie"))
        assertEquals(0, employees.account(employee.id).failedLoginCount)
        assertEquals(listOf("LOGIN_FAILED"), employees.auditActions(employee.id))
        assertEquals("ACCOUNT_SUSPENDED", employees.auditDetails(employee.id).single()?.get("reason"))
        val sessions =
            employees.inTransaction { RefreshSessionTable.selectAll().where { RefreshSessionTable.principalId eq employee.id }.count() }
        assertEquals(0L, sessions)
    }

    @Test
    fun `LGN-03 정지 계정도 비밀번호가 틀리면 상태를 드러내지 않고 INVALID_CREDENTIALS`() {
        val employee = employees.create(status = AccountStatus.SUSPENDED)

        val response = login(employee.email, WRONG_PASSWORD)

        assertEquals(401, response.status)
        assertEquals("INVALID_CREDENTIALS", json(response)["code"])
    }

    @Test
    fun `LGN-03 비활성화된 계정은 비밀번호가 맞아도 없는 계정과 같은 응답`() {
        val employee = employees.create(status = AccountStatus.DEACTIVATED)

        val deactivated = login(employee.email, PASSWORD)
        val unknown = login("nobody-${UUID.randomUUID()}@dozycoffee.test", PASSWORD)

        assertEquals(401, deactivated.status)
        assertEquals(json(unknown) - "traceId" - "instance", json(deactivated) - "traceId" - "instance")
    }

    @Test
    fun `아직 받지 않는 realm과 모르는 realm은 404`() {
        val employee = employees.create()

        assertEquals(404, login(employee.email, PASSWORD, realm = "partner").status)
        assertEquals("NOT_FOUND", json(login(employee.email, PASSWORD, realm = "unknown"))["code"])
    }

    @Test
    fun `비밀번호가 없으면 VALIDATION_FAILED`() {
        mockMvc
            .post("/realms/internal/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email": "kim@dozycoffee.test"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
                jsonPath("$.errors[0].field") { value("password") }
            }
    }

    @Test
    fun `로그인은 Authorization 헤더가 있어도 보지 않음`() {
        val employee = employees.create()

        val response = login(employee.email, PASSWORD, authorization = "Bearer not-a-token")

        assertEquals(200, response.status)
    }

    @Test
    fun `로그인 응답에도 X-Trace-Id를 넣음`() {
        val employee = employees.create()

        assertFalse(login(employee.email, PASSWORD).getHeader("X-Trace-Id").isNullOrBlank())
    }

    private fun login(
        email: String,
        password: String,
        realm: String = Realm.INTERNAL.pathValue,
        userAgent: String? = null,
        authorization: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/$realm/login") {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))
                userAgent?.let { header("User-Agent", it) }
                authorization?.let { header("Authorization", it) }
            }.andReturn()
            .response

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    /** 서비스가 스타터로 만드는 검증기. 공개키는 JWKS API에서 받습니다. */
    private fun serviceDecoder(audience: String) =
        DozyJwtDecoders.create(
            DozyAuthProperties(audience = audience, acceptedRealms = setOf(Realm.INTERNAL), issuerBaseUri = "http://localhost:8080"),
            ImmutableJWKSet(
                JWKSet.parse(
                    mockMvc
                        .get("/.well-known/jwks.json")
                        .andReturn()
                        .response.contentAsString,
                ),
            ),
        )

    private fun unknownAccountFailures(): Long =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .where { (AuditLogTable.action eq "LOGIN_FAILED") and AuditLogTable.actorId.isNull() and AuditLogTable.targetId.isNull() }
                .count()
        }
}
