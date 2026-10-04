package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.AudienceTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.RoleTable
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import com.dozycoffee.auth.server.support.TokenFixtures.SYSTEM
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * role 정의와 audience 관리 API (api/admin.md §5)와 `/admin` 인가 (api/conventions.md §2, GOV-14).
 *
 * 응답 필드 이름, 에러 code, 감사 action은 명세의 문자열 그대로 기대값으로 씁니다. 관리자의 role은 토큰의 role로 정합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class)
@ActiveProfiles("test")
class AdminRoleApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @Autowired
    lateinit var loadRole: LoadRolePort

    @Autowired
    lateinit var loadPrincipalRoles: LoadPrincipalRolesPort

    @Autowired
    lateinit var grantRole: GrantRolePort

    private val jsonMapper = JsonMapper.builder().build()

    /** API로 만든 role·audience. fixture가 모르는 데이터라 여기서 지웁니다. */
    private val createdRoles = mutableListOf<Long>()
    private val createdAudiences = mutableListOf<Long>()

    @AfterEach
    fun cleanUp() {
        // role.created_by가 직원을 참조하므로 직원보다 먼저 지웁니다
        employees.inTransaction {
            if (createdRoles.isNotEmpty()) {
                PrincipalRoleTable.deleteWhere { PrincipalRoleTable.roleId inList createdRoles }
                RoleTable.deleteWhere { RoleTable.id inList createdRoles }
            }
            if (createdAudiences.isNotEmpty()) AudienceTable.deleteWhere { AudienceTable.id inList createdAudiences }
        }
        employees.cleanUp()
    }

    // role 목록

    @Test
    fun `role 목록은 role마다 가진 principal 수를 응답`() {
        val role = employees.newRoleCode("wms")
        employees.create(roles = listOf(role))
        grant(employees.create(), role)

        get("/admin/roles?audience=wms", adminToken()).andExpect {
            status { isOk() }
            jsonPath("$.items[?(@.fullCode == '${role.value}')].audience") { value("wms") }
            jsonPath("$.items[?(@.fullCode == '${role.value}')].code") { value(role.code) }
            jsonPath("$.items[?(@.fullCode == '${role.value}')].isSystem") { value(false) }
            jsonPath("$.items[?(@.fullCode == '${role.value}')].grantedCount") { value(2) }
            jsonPath("$.items[?(@.audience != 'wms')]") { isEmpty() }
            jsonPath("$.page") { doesNotExist() }
        }
    }

    @Test
    fun `audience를 주지 않으면 system role을 포함한 모든 role을 응답`() {
        get("/admin/roles", ownerToken()).andExpect {
            status { isOk() }
            jsonPath("$.items[?(@.fullCode == 'auth:owner')].isSystem") { value(true) }
            jsonPath("$.items[?(@.fullCode == 'auth:admin')].isSystem") { value(true) }
        }
    }

    // role 등록

    @Test
    fun `GOV-13 admin이 role을 등록하면 아무에게도 부여되지 않은 role이 생기고 ROLE_DEFINED를 남김`() {
        val admin = employees.create()
        val code = employees.newRoleCode("wms")

        val body =
            post(
                "/admin/roles",
                adminToken(admin),
                mapOf(
                    "audience" to "wms",
                    "code" to code.code,
                    "name" to "입고 관리자",
                    "description" to "입고 등록",
                ),
            ).andExpect {
                status { isCreated() }
                jsonPath("$.audience") { value("wms") }
                jsonPath("$.code") { value(code.code) }
                jsonPath("$.fullCode") { value("wms:${code.code}") }
                jsonPath("$.name") { value("입고 관리자") }
                jsonPath("$.description") { value("입고 등록") }
                jsonPath("$.isSystem") { value(false) }
                jsonPath("$.grantedCount") { value(0) }
            }.andReturn()
                .response.contentAsString
        val roleId = trackRole(body)

        assertEquals(
            admin.id,
            employees.inTransaction { RoleTable.selectAll().where { RoleTable.id eq roleId }.single()[RoleTable.createdBy] },
        )
        val audit = audits("ROLE", roleId.toString()).single()
        assertEquals("ROLE_DEFINED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(mapOf("role" to "wms:${code.code}"), audit.detail)
    }

    @Test
    fun `같은 audience에 같은 code면 409 ROLE_CODE_DUPLICATED`() {
        val code = employees.newRoleCode("wms")
        employees.create(roles = listOf(code))

        post("/admin/roles", adminToken(), mapOf("audience" to "wms", "code" to code.code, "name" to "중복")).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ROLE_CODE_DUPLICATED") }
        }
    }

    @Test
    fun `없는 audience에 role을 등록하면 404 NOT_FOUND`() {
        post("/admin/roles", adminToken(), mapOf("audience" to "no_such_audience", "code" to "reader", "name" to "읽기")).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `DOM-03 role code 형식이 아니면 400 VALIDATION_FAILED`() {
        post("/admin/roles", adminToken(), mapOf("audience" to "wms", "code" to "Inbound-Manager", "name" to "입고")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value("code") }
        }
    }

    @Test
    fun `필수 값이 없으면 400 VALIDATION_FAILED`() {
        post("/admin/roles", adminToken(), mapOf("audience" to "wms")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[?(@.field == 'code')]") { isNotEmpty() }
            jsonPath("$.errors[?(@.field == 'name')]") { isNotEmpty() }
        }
    }

    // role 수정

    @Test
    fun `GOV-13 이름과 설명을 수정하고 ROLE_UPDATED에 바뀐 필드 이름만 남김`() {
        val admin = employees.create()
        val code = employees.newRoleCode("wms")
        val holder = employees.create(roles = listOf(code))
        val roleId = roleId(code)

        patch("/admin/roles/$roleId", adminToken(admin), mapOf("name" to "새 이름", "description" to "새 설명")).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(roleId) }
            jsonPath("$.fullCode") { value(code.value) }
            jsonPath("$.name") { value("새 이름") }
            jsonPath("$.description") { value("새 설명") }
            jsonPath("$.grantedCount") { value(1) }
        }

        assertEquals(listOf(code), employees.inTransaction { loadPrincipalRoles.findRoleCodes(holder.id) })
        val audit = audits("ROLE", roleId.toString()).single()
        assertEquals("ROLE_UPDATED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(mapOf("role" to code.value, "fields" to listOf("name", "description")), audit.detail)
    }

    @Test
    fun `보내지 않은 값은 그대로 두고 빈 설명은 설명을 지움`() {
        val code = employees.newRoleCode("wms")
        employees.create(roles = listOf(code))
        val roleId = roleId(code)
        patch("/admin/roles/$roleId", adminToken(), mapOf("description" to "설명")).andExpect { status { isOk() } }

        patch("/admin/roles/$roleId", adminToken(), mapOf("description" to "")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value(code.code) }
            jsonPath("$.description") { value(null) }
        }
    }

    @Test
    fun `GOV-13 code나 audience를 보내면 400 VALIDATION_FAILED`() {
        val code = employees.newRoleCode("wms")
        employees.create(roles = listOf(code))
        val roleId = roleId(code)

        patch("/admin/roles/$roleId", adminToken(), mapOf("code" to "other", "audience" to "catalog")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[?(@.field == 'code')]") { isNotEmpty() }
            jsonPath("$.errors[?(@.field == 'audience')]") { isNotEmpty() }
        }
        assertEquals(code, employees.inTransaction { loadRole.findRoleById(roleId) }?.code)
    }

    @Test
    fun `GOV-13 system role을 수정하면 403 FORBIDDEN`() {
        patch("/admin/roles/${roleId(RoleCode("auth", "admin"))}", ownerToken(), mapOf("name" to "관리자")).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `없는 role을 수정하면 404 NOT_FOUND`() {
        patch("/admin/roles/$UNKNOWN_ROLE_ID", adminToken(), mapOf("name" to "이름")).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    // role 삭제

    @Test
    fun `GOV-13 부여된 principal이 없는 role은 바로 삭제하고 ROLE_DELETED를 남김`() {
        val admin = employees.create()
        val code = employees.newRoleCode("wms")
        val roleId = trackRole(defineRole(code))

        delete("/admin/roles/$roleId", adminToken(admin)).andExpect { status { isNoContent() } }

        assertTrue(employees.inTransaction { loadRole.findRoleById(roleId) == null })
        val audit = audits("ROLE", roleId.toString()).last()
        assertEquals("ROLE_DELETED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(mapOf("role" to code.value, "revokedPrincipals" to 0), audit.detail)
    }

    @Test
    fun `GOV-13 부여된 principal이 있으면 409 ROLE_IN_USE이고 아무것도 바꾸지 않음`() {
        val code = employees.newRoleCode("wms")
        val holder = employees.create(roles = listOf(code))
        val roleId = roleId(code)

        delete("/admin/roles/$roleId", adminToken()).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ROLE_IN_USE") }
        }
        delete("/admin/roles/$roleId?revokeAll=false", adminToken()).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ROLE_IN_USE") }
        }

        assertEquals(code, employees.inTransaction { loadRole.findRoleById(roleId) }?.code)
        assertEquals(listOf(code), employees.inTransaction { loadPrincipalRoles.findRoleCodes(holder.id) })
        assertTrue(audits("ROLE", roleId.toString()).isEmpty())
    }

    @Test
    fun `GOV-13 revokeAll이면 모든 principal에게서 회수한 뒤 삭제하고 회수마다 ROLE_REVOKED, 마지막에 ROLE_DELETED를 남김`() {
        val admin = employees.create()
        val code = employees.newRoleCode("wms")
        val otherRole = employees.newRoleCode("wms")
        val first = employees.create(roles = listOf(code, otherRole))
        val second = employees.create().also { grant(it, code) }
        val roleId = roleId(code)

        delete("/admin/roles/$roleId?revokeAll=true", adminToken(admin)).andExpect { status { isNoContent() } }

        assertTrue(employees.inTransaction { loadRole.findRoleById(roleId) == null })
        assertEquals(listOf(otherRole), employees.inTransaction { loadPrincipalRoles.findRoleCodes(first.id) })
        assertEquals(emptyList<RoleCode>(), employees.inTransaction { loadPrincipalRoles.findRoleCodes(second.id) })
        for (holder in listOf(first, second)) {
            val revoked = audits("PRINCIPAL", holder.id.toString()).single()
            assertEquals("ROLE_REVOKED", revoked.action)
            assertEquals(admin.id, revoked.actorId)
            assertEquals(mapOf("roles" to listOf(code.value), "via" to "role_deleted"), revoked.detail)
        }
        val deleted = audits("ROLE", roleId.toString()).single()
        assertEquals("ROLE_DELETED", deleted.action)
        assertEquals(mapOf("role" to code.value, "revokedPrincipals" to 2), deleted.detail)
        assertTrue(allAuditIds().last() == deleted.id)
    }

    @Test
    fun `GOV-13 admin의 revokeAll은 owner와 admin이 가진 일반 role도 회수`() {
        val code = employees.newRoleCode("wms")
        val owner = employees.create(roles = listOf(code))
        employees.makeOwner(owner)
        val otherAdmin = employees.create().also { grant(it, code, RoleCode("auth", "admin")) }
        val roleId = roleId(code)

        delete("/admin/roles/$roleId?revokeAll=true", adminToken()).andExpect { status { isNoContent() } }

        assertEquals(listOf(RoleCode("auth", "owner")), employees.inTransaction { loadPrincipalRoles.findRoleCodes(owner.id) })
        assertEquals(listOf(RoleCode("auth", "admin")), employees.inTransaction { loadPrincipalRoles.findRoleCodes(otherAdmin.id) })
    }

    @Test
    fun `GOV-13 system role을 삭제하면 revokeAll이어도 403 FORBIDDEN`() {
        delete("/admin/roles/${roleId(RoleCode("auth", "admin"))}?revokeAll=true", ownerToken()).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertEquals(RoleCode("auth", "admin"), employees.inTransaction { loadRole.findRoleByCode(RoleCode("auth", "admin")) }?.code)
    }

    @Test
    fun `없는 role을 삭제하면 404 NOT_FOUND`() {
        delete("/admin/roles/$UNKNOWN_ROLE_ID", adminToken()).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    // audience

    @Test
    fun `audience 목록을 id 순서로 응답`() {
        get("/admin/audiences", adminToken()).andExpect {
            status { isOk() }
            jsonPath("$.items[?(@.code == 'auth')].name") { isNotEmpty() }
            jsonPath("$.items[?(@.code == 'wms')].id") { isNotEmpty() }
            jsonPath("$.page") { doesNotExist() }
        }
    }

    @Test
    fun `GOV-13 owner가 audience를 추가하면 AUDIENCE_CREATED를 남김`() {
        val owner = employees.create()
        val code = newAudienceCode()

        val body =
            post("/admin/audiences", ownerToken(owner), mapOf("code" to code, "name" to "주문"))
                .andExpect {
                    status { isCreated() }
                    jsonPath("$.code") { value(code) }
                    jsonPath("$.name") { value("주문") }
                    jsonPath("$.description") { value(null) }
                }.andReturn()
                .response.contentAsString
        val audienceId = jsonMapper.readTree(body).get("id").asLong()
        createdAudiences += audienceId

        val audit = audits("AUDIENCE", audienceId.toString()).single()
        assertEquals("AUDIENCE_CREATED", audit.action)
        assertEquals(owner.id, audit.actorId)
        assertEquals(mapOf("audience" to code), audit.detail)
    }

    @Test
    fun `같은 code의 audience면 409 AUDIENCE_CODE_DUPLICATED`() {
        post("/admin/audiences", ownerToken(), mapOf("code" to "wms", "name" to "창고")).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("AUDIENCE_CODE_DUPLICATED") }
        }
    }

    @Test
    fun `DOM-03 audience code 형식이 아니면 400 VALIDATION_FAILED`() {
        post("/admin/audiences", ownerToken(), mapOf("code" to "1order", "name" to "주문")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value("code") }
        }
    }

    @Test
    fun `GOV-13 admin이 audience를 추가하면 403 FORBIDDEN`() {
        val code = newAudienceCode()

        post("/admin/audiences", adminToken(), mapOf("code" to code, "name" to "주문")).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertTrue(employees.inTransaction { AudienceTable.selectAll().where { AudienceTable.code eq code }.empty() })
    }

    // 인가

    @Test
    fun `GOV-14 auth audience의 다른 role만 가진 직원은 403 FORBIDDEN`() {
        val reader = employees.create()
        val token = tokens.issue(reader.key, roles = listOf(employees.newRoleCode("auth").value))

        get("/admin/roles", token).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        get("/admin/audiences", token).andExpect { status { isForbidden() } }
        post("/admin/roles", token, mapOf("audience" to "wms", "code" to "reader", "name" to "읽기")).andExpect { status { isForbidden() } }
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        mockMvc.get("/admin/roles").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `aud에 auth가 없는 토큰은 401 UNAUTHENTICATED`() {
        val employee = employees.create()

        get("/admin/roles", tokens.issue(employee.key, roles = listOf("wms:inbound_manager"))).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `system token은 403 FORBIDDEN`() {
        get("/admin/roles", tokens.issue(SYSTEM, roles = listOf("auth:partner_reader"))).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    private fun ownerToken(owner: CreatedEmployee = employees.create()): String = tokens.issue(owner.key, roles = listOf("auth:owner"))

    private fun adminToken(admin: CreatedEmployee = employees.create()): String = tokens.issue(admin.key, roles = listOf("auth:admin"))

    private fun get(
        path: String,
        token: String,
    ): ResultActionsDsl = mockMvc.get(path) { header("Authorization", "Bearer $token") }

    private fun post(
        path: String,
        token: String,
        body: Map<String, Any?>,
    ): ResultActionsDsl =
        mockMvc.post(path) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
        }

    private fun patch(
        path: String,
        token: String,
        body: Map<String, Any?>,
    ): ResultActionsDsl =
        mockMvc.patch(path) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
        }

    private fun delete(
        path: String,
        token: String,
    ): ResultActionsDsl = mockMvc.delete(path) { header("Authorization", "Bearer $token") }

    /** API로 role을 등록하고 응답 본문을 돌려줍니다. */
    private fun defineRole(code: RoleCode): String =
        post("/admin/roles", adminToken(), mapOf("audience" to code.audience, "code" to code.code, "name" to code.code))
            .andReturn()
            .response.contentAsString

    private fun trackRole(responseBody: String): Long =
        jsonMapper
            .readTree(responseBody)
            .get("id")
            .asLong()
            .also { createdRoles += it }

    /** 이미 정의된 role을 [employee]에게 부여합니다. 감사 로그는 남기지 않습니다. */
    private fun grant(
        employee: CreatedEmployee,
        vararg codes: RoleCode,
    ) {
        codes.forEach { code -> employees.inTransaction { grantRole.grant(RoleGrant(employee.id, roleId(code), null, GRANTED_AT)) } }
    }

    private fun roleId(code: RoleCode): Long = checkNotNull(employees.inTransaction { loadRole.findRoleByCode(code) }).id

    private fun newAudienceCode(): String =
        "aud_" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(20)

    private fun audits(
        targetType: String,
        targetId: String,
    ): List<AuditRow> =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .where { (AuditLogTable.targetType eq targetType) and (AuditLogTable.targetId eq targetId) }
                .orderBy(AuditLogTable.id)
                .map { AuditRow(it[AuditLogTable.id], it[AuditLogTable.action], it[AuditLogTable.actorId], it[AuditLogTable.detail]) }
        }

    private fun allAuditIds(): List<Long> =
        employees.inTransaction {
            AuditLogTable.selectAll().orderBy(AuditLogTable.id).map { it[AuditLogTable.id] }
        }

    private data class AuditRow(
        val id: Long,
        val action: String,
        val actorId: UUID?,
        val detail: Map<String, Any?>?,
    )

    private companion object {
        private const val UNKNOWN_ROLE_ID = 999_999_999L
        private val GRANTED_AT: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
