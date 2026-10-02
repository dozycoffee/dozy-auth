package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import com.dozycoffee.auth.server.support.TokenFixtures.PARTNER
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * 내 정보 API (api/auth.md 내 정보)와 `/realms/{realm}` 아래 본인 API의 토큰 검사 (api/conventions.md §2).
 *
 * 응답 필드 이름과 에러 code는 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class)
@ActiveProfiles("test")
class MeApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `로그인한 직원의 표시 정보와 DB의 현재 role을 응답`() {
        val wmsRole = employees.newRoleCode("wms")
        val employee = employees.create(roles = listOf(wmsRole), name = "김도윤")
        val accessToken = login(employee.email)

        me(accessToken).andExpect {
            status { isOk() }
            jsonPath("$.principalType") { value("employee") }
            jsonPath("$.principalId") { value(employee.id.toString()) }
            jsonPath("$.name") { value("김도윤") }
            jsonPath("$.email") { value(employee.email) }
            jsonPath("$.roles.length()") { value(1) }
            jsonPath("$.roles[0]") { value(wmsRole.value) }
        }
    }

    @Test
    fun `role이 없어 aud가 빈 직원도 조회할 수 있음`() {
        val employee = employees.create()

        me(tokens.issue(employee.key)).andExpect {
            status { isOk() }
            jsonPath("$.roles") { isEmpty() }
        }
    }

    @Test
    fun `aud에 auth가 없는 토큰도 받음`() {
        val catalogRole = employees.newRoleCode("catalog")
        val employee = employees.create(roles = listOf(catalogRole))

        me(tokens.issue(employee.key, roles = listOf(catalogRole.value))).andExpect { status { isOk() } }
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        mockMvc.get("/realms/internal/me").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `서명이 틀린 토큰은 401`() {
        val employee = employees.create()
        val token = tokens.issue(employee.key)
        val tampered = token.dropLast(4) + if (token.endsWith("AAAA")) "BBBB" else "AAAA"

        me(tampered).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `iss의 realm이 경로의 realm과 다르면 401`() {
        val employee = employees.create()

        me(tokens.issue(employee.key), realm = "partner").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `서버가 받지 않는 realm의 토큰은 401`() {
        me(tokens.issue(PARTNER), realm = "partner").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `system token은 403 FORBIDDEN`() {
        me(tokens.issue(SYSTEM, roles = listOf("auth:partner_reader"))).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `토큰의 주체가 없는 계정이면 401`() {
        val unknown = PrincipalKey(PrincipalType.EMPLOYEE, UUID.fromString("0199a3c4-0000-7000-8000-000000000000"))

        me(tokens.issue(unknown)).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `비활성화된 계정이면 401`() {
        val employee = employees.create(status = AccountStatus.DEACTIVATED)

        me(tokens.issue(employee.key)).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `모르는 realm 경로는 토큰이 맞아도 iss와 달라 401`() {
        val employee = employees.create()

        me(tokens.issue(employee.key), realm = "unknown").andExpect { status { isUnauthorized() } }
    }

    private fun me(
        accessToken: String,
        realm: String = Realm.INTERNAL.pathValue,
    ): ResultActionsDsl = mockMvc.get("/realms/$realm/me") { header("Authorization", "Bearer $accessToken") }

    private fun login(email: String): String {
        val body =
            mockMvc
                .post("/realms/internal/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to PASSWORD))
                }.andReturn()
                .response.contentAsString
        return jsonMapper.readTree(body).get("accessToken").asString()
    }
}
