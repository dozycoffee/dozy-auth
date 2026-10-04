package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.RoleTable
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LockRolePort
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import jakarta.servlet.http.Cookie
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.HttpCookie
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * role 부여·회수 API (api/admin.md §4, GOV-02~08, GOV-15, AUD-08, SES-05).
 *
 * 관리자의 필요 role(GOV-14)은 토큰으로, 관리 등급은 DB의 role로 정하므로 관리자는 DB와 토큰에 같은 role을 줍니다.
 * 에러 code와 감사 action은 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class)
@ActiveProfiles("test")
class AdminPrincipalRoleApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @Autowired
    lateinit var loadRole: LoadRolePort

    @Autowired
    lateinit var lockRole: LockRolePort

    @Autowired
    lateinit var loadPrincipalRoles: LoadPrincipalRolesPort

    @Autowired
    lateinit var grantRole: GrantRolePort

    private val jsonMapper = JsonMapper.builder().build()

    @AfterEach
    fun cleanUp() {
        employees.cleanUp()
    }

    // role 부여

    @Test
    fun `admin이 role 하나를 부여하면 204이고 ROLE_GRANTED에 그 role을 남김`() {
        val admin = admin()
        val target = employees.create()
        val role = definedRole("wms")

        grant(adminToken(admin), target.id, role.value).andExpect { status { isNoContent() } }

        assertEquals(listOf(role), rolesOf(target.id))
        val audit = audits(target.id).single()
        assertEquals("ROLE_GRANTED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(mapOf("roles" to listOf(role.value)), audit.detail)
    }

    @Test
    fun `여러 role을 한 번에 부여하면 ROLE_GRANTED 한 건에 모두 담음`() {
        val target = employees.create()
        val wms = definedRole("wms")
        val catalog = definedRole("catalog")

        grant(adminToken(), target.id, wms.value, catalog.value).andExpect { status { isNoContent() } }

        assertEquals(setOf(wms, catalog), rolesOf(target.id).toSet())
        val audit = audits(target.id).single()
        assertEquals("ROLE_GRANTED", audit.action)
        assertEquals(setOf(wms.value, catalog.value), (audit.detail?.get("roles") as List<*>).toSet())
    }

    @Test
    fun `GOV-08 이미 가진 role은 무시하고 새로 부여한 role만 ROLE_GRANTED에 남김`() {
        val held = employees.newRoleCode("wms")
        val target = employees.create(roles = listOf(held))
        val added = definedRole("wms")

        grant(adminToken(), target.id, held.value, added.value).andExpect { status { isNoContent() } }

        assertEquals(setOf(held, added), rolesOf(target.id).toSet())
        assertEquals(mapOf("roles" to listOf(added.value)), audits(target.id).single().detail)
    }

    @Test
    fun `GOV-08 가진 role만 다시 부여하면 204이고 감사 로그를 남기지 않음`() {
        val held = employees.newRoleCode("wms")
        val target = employees.create(roles = listOf(held))

        grant(adminToken(), target.id, held.value).andExpect { status { isNoContent() } }

        assertEquals(listOf(held), rolesOf(target.id))
        assertTrue(audits(target.id).isEmpty())
    }

    @Test
    fun `GOV-08 없는 role이 하나라도 있으면 404 NOT_FOUND이고 아무것도 부여하지 않음`() {
        val target = employees.create()
        val existing = definedRole("wms")

        grant(adminToken(), target.id, existing.value, "wms:no_such_role").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }

        assertTrue(rolesOf(target.id).isEmpty())
        assertTrue(audits(target.id).isEmpty())
    }

    @Test
    fun `GOV-08 규칙에 어긋나는 role이 하나라도 있으면 아무것도 부여하지 않음`() {
        val target = employees.create()
        val existing = definedRole("wms")

        grant(adminToken(), target.id, existing.value, "auth:admin").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }

        assertTrue(rolesOf(target.id).isEmpty())
    }

    @Test
    fun `없는 principal이면 404 NOT_FOUND`() {
        grant(adminToken(), UUID.randomUUID(), definedRole("wms").value).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `GOV-15 없는 principal은 다른 규칙보다 먼저 404 NOT_FOUND`() {
        grant(adminToken(), UUID.randomUUID(), "auth:owner").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `GOV-05 admin이 auth admin을 부여하면 403 FORBIDDEN`() {
        val target = employees.create()

        grant(adminToken(), target.id, "auth:admin").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertTrue(rolesOf(target.id).isEmpty())
    }

    @Test
    fun `GOV-05 owner가 auth admin을 부여해 admin을 임명하고 ROLE_GRANTED를 남김`() {
        val owner = owner()
        val target = employees.create()

        grant(ownerToken(owner), target.id, "auth:admin").andExpect { status { isNoContent() } }

        assertEquals(listOf(RoleCode("auth", "admin")), rolesOf(target.id))
        val audit = audits(target.id).single()
        assertEquals("ROLE_GRANTED", audit.action)
        assertEquals(owner.id, audit.actorId)
        assertEquals(mapOf("roles" to listOf("auth:admin")), audit.detail)
    }

    @Test
    fun `GOV-05 owner도 auth owner는 부여할 수 없어 403 FORBIDDEN`() {
        val target = employees.create()

        grant(ownerToken(owner()), target.id, "auth:owner").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertTrue(rolesOf(target.id).isEmpty())
    }

    @Test
    fun `GOV-04 자기 자신에게 부여하면 403 SELF_GRANT_NOT_ALLOWED`() {
        val admin = admin()

        grant(adminToken(admin), admin.id, definedRole("wms").value).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("SELF_GRANT_NOT_ALLOWED") }
        }
    }

    @Test
    fun `GOV-15 자기 자신에게 auth admin을 부여하는 admin은 GOV-05가 먼저라 403 FORBIDDEN`() {
        val admin = admin()

        grant(adminToken(admin), admin.id, "auth:admin").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `GOV-02 admin이 owner에게 부여하면 403 PROTECTED_ACCOUNT`() {
        val owner = owner()

        grant(adminToken(), owner.id, definedRole("wms").value).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("PROTECTED_ACCOUNT") }
        }
    }

    @Test
    fun `GOV-02 admin이 다른 admin에게 부여하면 403 PROTECTED_ACCOUNT`() {
        val otherAdmin = admin()

        grant(adminToken(), otherAdmin.id, definedRole("wms").value).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("PROTECTED_ACCOUNT") }
        }
        assertEquals(listOf(RoleCode("auth", "admin")), rolesOf(otherAdmin.id))
    }

    @Test
    fun `GOV-02 owner는 admin에게 일반 role을 부여할 수 있음`() {
        val target = admin()
        val role = definedRole("wms")

        grant(ownerToken(owner()), target.id, role.value).andExpect { status { isNoContent() } }

        assertEquals(setOf(RoleCode("auth", "admin"), role), rolesOf(target.id).toSet())
    }

    @Test
    fun `해임된 admin은 토큰에 auth admin이 남아 있어도 관리 등급이 없어 403 FORBIDDEN`() {
        val dismissed = employees.create()
        val target = employees.create()

        grant(adminToken(dismissed), target.id, definedRole("wms").value).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertTrue(rolesOf(target.id).isEmpty())
    }

    @Test
    fun `GOV-06 system client에는 일반 role을 부여할 수 있음`() {
        val client = principal(PrincipalType.SYSTEM, AccountStatus.ACTIVE)
        val role = definedRole("store")

        grant(adminToken(), client, role.value).andExpect { status { isNoContent() } }

        assertEquals(listOf(role), rolesOf(client))
    }

    @Test
    fun `GOV-06 system client에 system role을 부여하면 403 FORBIDDEN`() {
        val client = principal(PrincipalType.SYSTEM, AccountStatus.ACTIVE)

        grant(ownerToken(owner()), client, "auth:admin").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertTrue(rolesOf(client).isEmpty())
    }

    @Test
    fun `GOV-06 파트너에게 부여하면 409 INVALID_STATE`() {
        val partner = principal(PrincipalType.PARTNER, AccountStatus.ACTIVE)

        grant(adminToken(), partner, definedRole("store").value).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INVALID_STATE") }
        }
        assertTrue(rolesOf(partner).isEmpty())
    }

    @Test
    fun `GOV-07 SUSPENDED 직원에게 부여하면 409 INVALID_STATE`() {
        val target = employees.create(status = AccountStatus.SUSPENDED)

        grant(adminToken(), target.id, definedRole("wms").value).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INVALID_STATE") }
        }
        assertTrue(rolesOf(target.id).isEmpty())
    }

    @Test
    fun `GOV-07 DEACTIVATED 직원에게 부여하면 409 INVALID_STATE`() {
        val target = employees.create(status = AccountStatus.DEACTIVATED)

        grant(adminToken(), target.id, definedRole("wms").value).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("INVALID_STATE") }
        }
    }

    @Test
    fun `GOV-07 PENDING 직원에게는 부여할 수 있음`() {
        val target = employees.create(status = AccountStatus.PENDING, password = null)
        val role = definedRole("wms")

        grant(adminToken(), target.id, role.value).andExpect { status { isNoContent() } }

        assertEquals(listOf(role), rolesOf(target.id))
    }

    @Test
    fun `SES-05 부여한 role은 대상의 다음 토큰 갱신부터 토큰에 담김`() {
        val target = employees.create()
        val role = definedRole("wms")
        val refreshToken = login(target.email)

        grant(adminToken(), target.id, role.value).andExpect { status { isNoContent() } }

        val claims = accessTokenClaims(refresh(refreshToken))
        assertEquals(listOf(role.value), claims["roles"])
        assertEquals(listOf("wms"), claims["aud"])
    }

    @Test
    fun `roles가 비었거나 형식이 틀리면 400 VALIDATION_FAILED`() {
        val target = employees.create()

        grant(adminToken(), target.id).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
        grant(adminToken(), target.id, "wms-inbound").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `동시에 삭제된 role의 부여는 삭제가 끝난 뒤 404 NOT_FOUND`() {
        val target = employees.create()
        val role = definedRole("wms")
        val roleId = roleId(role)
        val token = adminToken()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)

        // role 삭제 트랜잭션이 role을 잠그고 지운 채 커밋을 기다리는 동안 부여 요청을 보냅니다
        val deletion =
            CompletableFuture.runAsync {
                employees.inTransaction {
                    checkNotNull(lockRole.lockRoleForDelete(roleId))
                    RoleTable.deleteWhere { RoleTable.id eq roleId }
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        locked.await(WAIT_SECONDS, TimeUnit.SECONDS)
        val granting = CompletableFuture.supplyAsync { grant(token, target.id, role.value).andReturn().response }
        awaitLockWaiter()
        release.countDown()
        deletion.get(WAIT_SECONDS, TimeUnit.SECONDS)

        val response = granting.get(WAIT_SECONDS, TimeUnit.SECONDS)
        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", json(response)["code"])
        assertTrue(rolesOf(target.id).isEmpty())
    }

    @Test
    fun `GOV-13 부여 중인 role의 삭제는 부여가 끝난 뒤 새 부여까지 세어 409 ROLE_IN_USE`() {
        val target = employees.create()
        val role = definedRole("wms")
        val roleId = roleId(role)
        val token = adminToken()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)

        // 부여 트랜잭션이 role을 잠그고 부여한 채 커밋을 기다리는 동안 삭제 요청을 보냅니다
        val granting =
            CompletableFuture.runAsync {
                employees.inTransaction {
                    checkNotNull(lockRole.lockRolesForGrant(listOf(role)).singleOrNull())
                    grantRole.grant(RoleGrant(target.id, roleId, null, GRANTED_AT))
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        locked.await(WAIT_SECONDS, TimeUnit.SECONDS)
        val deleting = CompletableFuture.supplyAsync { delete(token, "/admin/roles/$roleId").andReturn().response }
        awaitLockWaiter()
        release.countDown()
        granting.get(WAIT_SECONDS, TimeUnit.SECONDS)

        val response = deleting.get(WAIT_SECONDS, TimeUnit.SECONDS)
        assertEquals(409, response.status)
        assertEquals("ROLE_IN_USE", json(response)["code"])
        assertEquals(listOf(role), rolesOf(target.id))
    }

    // role 회수

    @Test
    fun `가진 role을 회수하면 204이고 ROLE_REVOKED를 남김`() {
        val admin = admin()
        val role = employees.newRoleCode("wms")
        val kept = employees.newRoleCode("wms")
        val target = employees.create(roles = listOf(role, kept))

        revoke(adminToken(admin), target.id, role.value).andExpect { status { isNoContent() } }

        assertEquals(listOf(kept), rolesOf(target.id))
        val audit = audits(target.id).single()
        assertEquals("ROLE_REVOKED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(mapOf("roles" to listOf(role.value)), audit.detail)
    }

    @Test
    fun `GOV-08 가지지 않은 role을 회수해도 204이고 감사 로그를 남기지 않음`() {
        val target = employees.create()

        revoke(adminToken(), target.id, definedRole("wms").value).andExpect { status { isNoContent() } }

        assertTrue(audits(target.id).isEmpty())
    }

    @Test
    fun `정의되지 않은 role을 회수하면 404 NOT_FOUND`() {
        revoke(adminToken(), employees.create().id, "wms:no_such_role").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `없는 principal에서 회수하면 404 NOT_FOUND`() {
        revoke(adminToken(), UUID.randomUUID(), definedRole("wms").value).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `GOV-05 owner가 auth admin을 회수해 admin을 해임하고 ROLE_REVOKED를 남김`() {
        val owner = owner()
        val target = admin()

        revoke(ownerToken(owner), target.id, "auth:admin").andExpect { status { isNoContent() } }

        assertTrue(rolesOf(target.id).isEmpty())
        val audit = audits(target.id).single()
        assertEquals("ROLE_REVOKED", audit.action)
        assertEquals(owner.id, audit.actorId)
        assertEquals(mapOf("roles" to listOf("auth:admin")), audit.detail)
    }

    @Test
    fun `GOV-05 admin이 auth admin을 회수하면 403 FORBIDDEN`() {
        val target = admin()

        revoke(adminToken(), target.id, "auth:admin").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertEquals(listOf(RoleCode("auth", "admin")), rolesOf(target.id))
    }

    @Test
    fun `GOV-05 auth owner는 owner 자신도 회수할 수 없어 403 FORBIDDEN`() {
        val owner = owner()

        revoke(ownerToken(owner), owner.id, "auth:owner").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        assertEquals(listOf(RoleCode("auth", "owner")), rolesOf(owner.id))
    }

    @Test
    fun `GOV-02 admin이 owner·admin에게서 일반 role을 회수하면 403 PROTECTED_ACCOUNT`() {
        val role = definedRole("wms")
        val owner = owner()
        val otherAdmin = admin()
        grantInDb(owner.id, role)
        grantInDb(otherAdmin.id, role)

        for (target in listOf(owner, otherAdmin)) {
            revoke(adminToken(), target.id, role.value).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("PROTECTED_ACCOUNT") }
            }
            assertTrue(role in rolesOf(target.id))
        }
    }

    @Test
    fun `회수는 정지된 계정에도 할 수 있음`() {
        val role = employees.newRoleCode("wms")
        val target = employees.create(status = AccountStatus.SUSPENDED, roles = listOf(role))

        revoke(adminToken(), target.id, role.value).andExpect { status { isNoContent() } }

        assertTrue(rolesOf(target.id).isEmpty())
    }

    @Test
    fun `경로의 role 형식이 틀리면 400 VALIDATION_FAILED`() {
        revoke(adminToken(), employees.create().id, "wms-inbound").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    // 인가

    @Test
    fun `GOV-14 auth owner나 auth admin이 없는 직원은 403 FORBIDDEN`() {
        val employee = employees.create()
        val target = employees.create()
        val token = tokens.issue(employee.key, roles = listOf(employees.newRoleCode("auth").value))

        grant(token, target.id, definedRole("wms").value).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
        revoke(token, target.id, "wms:anything").andExpect { status { isForbidden() } }
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        mockMvc
            .post("/admin/principals/${UUID.randomUUID()}/roles") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"roles":["wms:reader"]}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHENTICATED") }
            }
        mockMvc.delete("/admin/principals/${UUID.randomUUID()}/roles/wms:reader").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    private fun owner(): CreatedEmployee = employees.create().also { employees.makeOwner(it) }

    private fun admin(): CreatedEmployee = employees.create().also { grantInDb(it.id, RoleCode("auth", "admin")) }

    private fun ownerToken(owner: CreatedEmployee): String = tokens.issue(owner.key, roles = listOf("auth:owner"))

    private fun adminToken(admin: CreatedEmployee = admin()): String = tokens.issue(admin.key, roles = listOf("auth:admin"))

    /** 새 일반 role을 정의합니다. 정의만 하기 위해 임시 직원에게 부여했다가 회수합니다. */
    private fun definedRole(audience: String): RoleCode {
        val code = employees.newRoleCode(audience)
        val holder = employees.create(roles = listOf(code))
        employees.inTransaction {
            PrincipalRoleTable.deleteWhere { PrincipalRoleTable.principalId eq holder.id }
        }
        return code
    }

    /** 감사 로그 없이 DB에 바로 부여합니다. */
    private fun grantInDb(
        principalId: UUID,
        code: RoleCode,
    ) {
        employees.inTransaction { grantRole.grant(RoleGrant(principalId, roleId(code), null, GRANTED_AT)) }
    }

    /** 직원이 아닌 principal (system client, 파트너). 규칙 검사에 필요한 principal 행만 만듭니다. */
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

    private fun roleId(code: RoleCode): Long = checkNotNull(employees.inTransaction { loadRole.findRoleByCode(code) }).id

    private fun rolesOf(principalId: UUID): List<RoleCode> = employees.inTransaction { loadPrincipalRoles.findRoleCodes(principalId) }

    private fun grant(
        token: String,
        principalId: UUID,
        vararg roles: String,
    ): ResultActionsDsl =
        mockMvc.post("/admin/principals/$principalId/roles") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(mapOf("roles" to roles.toList()))
        }

    private fun revoke(
        token: String,
        principalId: UUID,
        role: String,
    ): ResultActionsDsl = delete(token, "/admin/principals/$principalId/roles/$role")

    private fun delete(
        token: String,
        path: String,
    ): ResultActionsDsl = mockMvc.delete(path) { header("Authorization", "Bearer $token") }

    private fun login(email: String): String {
        val response =
            mockMvc
                .post("/realms/internal/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(mapOf("email" to email, "password" to TestEmployees.PASSWORD))
                }.andReturn()
                .response
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

    /** access token의 payload. 서명은 다른 테스트가 검증하므로 여기서는 읽기만 합니다. */
    private fun accessTokenClaims(response: MockHttpServletResponse): Map<String, Any?> {
        check(response.status == 200) { "갱신 실패: ${response.status}" }
        val accessToken = json(response)["accessToken"] as String
        val payload = Base64.getUrlDecoder().decode(accessToken.split('.')[1])
        @Suppress("UNCHECKED_CAST")
        return jsonMapper.readValue(payload, Map::class.java) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    /** 다른 트랜잭션이 행 잠금을 기다리기 시작할 때까지 기다립니다. */
    private fun awaitLockWaiter() {
        repeat((WAIT_SECONDS * 1000 / POLL_MILLIS).toInt()) {
            val waiting =
                employees.inTransaction {
                    TransactionManager.current().exec("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") {
                        it.next()
                        it.getInt(1)
                    } ?: 0
                }
            if (waiting > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("잠금을 기다리는 트랜잭션이 없음")
    }

    private fun audits(principalId: UUID): List<AuditRow> =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .where { (AuditLogTable.targetType eq "PRINCIPAL") and (AuditLogTable.targetId eq principalId.toString()) }
                .orderBy(AuditLogTable.id)
                .map { AuditRow(it[AuditLogTable.action], it[AuditLogTable.actorId], it[AuditLogTable.detail]) }
        }

    private data class AuditRow(
        val action: String,
        val actorId: UUID?,
        val detail: Map<String, Any?>?,
    )

    private companion object {
        /** test 프로필의 CORS 허용 origin (`application-test.yaml`). */
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 20L
        val GRANTED_AT: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
