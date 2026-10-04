package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * system client 목록·등록, secret 재발급 (api/admin.md §6, CLI-01~05, GOV-05·06·14·15, AUD-01·08, SEC-01·03).
 * 실제 DB에 커밋하며 확인하고, 발급한 secret은 서비스 토큰 발급 API(`/realms/internal/token`)로 확인합니다.
 *
 * 에러 code, 감사 action과 detail은 명세의 문자열 그대로 기대값으로 씁니다. 관리자의 관리 등급은 DB의 role로 정하므로
 * 관리자도 DB에 role을 부여하고, 토큰에도 같은 role을 담습니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
class AdminSystemClientApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @Autowired
    lateinit var clock: Clock

    private val jsonMapper = JsonMapper.builder().build()

    private val clients = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        if (clients.isNotEmpty()) {
            employees.inTransaction { SystemClientTable.deleteWhere { SystemClientTable.principalId inList clients } }
            clients.clear()
        }
        employees.cleanUp()
    }

    // 인가 (GOV-14)

    @Test
    fun `GOV-14 auth owner·admin role이 없는 직원은 세 API 모두 403 FORBIDDEN`() {
        val employee = employees.create()
        // aud에 auth가 있도록 auth audience의 일반 role을 담습니다
        val token = tokens.issue(employee.key, roles = listOf(employees.newRoleCode("auth").value))
        val existing = register(adminToken(admin()), newClientId())

        val responses =
            listOf(
                list(token),
                registerResponse(token, newClientId()),
                rotate(existing.principalId, token),
            )

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("FORBIDDEN", json(it)["code"])
        }
        assertEquals(listOf(existing.principalId), systemPrincipalIds())
    }

    @Test
    fun `GOV-14 토큰에 auth admin이 있어도 DB에서 회수됐으면 등록과 재발급은 403 FORBIDDEN`() {
        val existing = register(adminToken(admin()), newClientId())
        val dismissed = employees.create()
        val token = adminToken(dismissed)

        listOf(registerResponse(token, newClientId()), rotate(existing.principalId, token)).forEach {
            assertEquals(403, it.status)
            assertEquals("FORBIDDEN", json(it)["code"])
        }
        assertEquals(listOf(existing.principalId), systemPrincipalIds())
        assertEquals(200, requestSystemToken(existing.clientId, existing.secret).status)
    }

    @Test
    fun `토큰이 없으면 401 UNAUTHENTICATED`() {
        val response = list(null)

        assertEquals(401, response.status)
        assertEquals("UNAUTHENTICATED", json(response)["code"])
    }

    // 등록

    @Test
    fun `CLI-02 등록하면 201로 secret을 한 번 돌려주고 그 secret으로 받은 system token의 aud는 부여한 role의 audience`() {
        val catalogRole = employees.newRoleCode("catalog").also(employees::defineRole)
        val storeRole = employees.newRoleCode("store").also(employees::defineRole)
        val clientId = newClientId()

        val response = registerResponse(adminToken(admin()), clientId, roles = listOf(catalogRole.value, storeRole.value))

        assertEquals(201, response.status)
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL))
        val body = json(response)
        assertEquals(setOf("principalId", "clientId", "clientSecret"), body.keys)
        assertEquals(clientId, body["clientId"])
        val client = track(body)
        val token = requestSystemToken(clientId, client.secret)
        assertEquals(200, token.status)
        val claims = jwtClaims(json(token)["access_token"] as String)
        assertEquals(setOf("catalog", "store"), (claims["aud"] as List<*>).toSet())
        assertEquals(setOf(catalogRole.value, storeRole.value), (claims["roles"] as List<*>).toSet())
        assertEquals("system:${client.principalId}", claims["sub"])
    }

    @Test
    fun `CLI-04 등록한 client는 system 타입 ACTIVE이고 secret은 SHA-256 해시만 저장`() {
        val before = clock.instant()

        val client = register(adminToken(admin()), newClientId(), name = "Store 서비스")

        val account = employees.account(client.principalId)
        assertEquals(PrincipalType.SYSTEM, account.type)
        assertEquals(AccountStatus.ACTIVE, account.status)
        val row = clientRow(client.principalId)
        assertEquals(client.clientId, row[SystemClientTable.clientId])
        assertEquals("Store 서비스", row[SystemClientTable.name])
        assertEquals(SecretHash.of(client.secret).hex, row[SystemClientTable.clientSecretHash])
        assertFalse(row[SystemClientTable.secretRotatedAt].isBefore(before))
        assertEquals(row[SystemClientTable.secretRotatedAt], row[SystemClientTable.createdAt])
    }

    @Test
    fun `CLI-02 secret은 secret-bytes 난수이고 등록마다 다름`() {
        val token = adminToken(admin())

        val first = register(token, newClientId())
        val second = register(token, newClientId())

        assertEquals(AuthPolicy.SECRET_BYTES, Base64.getUrlDecoder().decode(first.secret).size)
        assertNotEquals(first.secret, second.secret)
    }

    @Test
    fun `등록은 SYSTEM_CLIENT_REGISTERED에 clientId를, role을 지정했으면 ROLE_GRANTED에 부여한 role을 남김`() {
        val admin = admin()
        val first = employees.newRoleCode("wms").also(employees::defineRole)
        val second = employees.newRoleCode("catalog").also(employees::defineRole)
        val clientId = newClientId()

        val client = register(adminToken(admin), clientId, roles = listOf(second.value, first.value, first.value))

        val audits = audits(client.principalId)
        assertEquals(listOf("SYSTEM_CLIENT_REGISTERED", "ROLE_GRANTED"), audits.map { it.action })
        audits.forEach { assertEquals(admin.id, it.actorId) }
        assertEquals(mapOf("clientId" to clientId), audits[0].detail)
        assertEquals(mapOf("roles" to listOf(first.value, second.value).sorted()), audits[1].detail)
    }

    @Test
    fun `role 없이 등록하면 SYSTEM_CLIENT_REGISTERED만 남기고 토큰의 aud는 비어 있음`() {
        val client = register(adminToken(admin()), newClientId(), roles = emptyList())

        assertEquals(listOf("SYSTEM_CLIENT_REGISTERED"), audits(client.principalId).map { it.action })
        val claims = jwtClaims(json(requestSystemToken(client.clientId, client.secret))["access_token"] as String)
        assertEquals(emptyList<Any>(), claims["aud"] ?: emptyList<Any>())
    }

    @Test
    fun `같은 clientId로 다시 등록하면 409 CLIENT_ID_DUPLICATED이고 계정과 감사 로그를 남기지 않음`() {
        val token = adminToken(admin())
        val clientId = newClientId()
        val existing = register(token, clientId)

        val response = registerResponse(token, clientId)

        assertEquals(409, response.status)
        assertEquals("CLIENT_ID_DUPLICATED", json(response)["code"])
        assertEquals(listOf(existing.principalId), systemPrincipalIds())
        assertEquals(listOf("SYSTEM_CLIENT_REGISTERED"), allAuditActions())
        assertEquals(200, requestSystemToken(clientId, existing.secret).status)
    }

    @Test
    fun `CLI-01 clientId가 svc-서비스명 형식이 아니면 400 VALIDATION_FAILED`() {
        val token = adminToken(admin())
        val invalid =
            listOf(
                null,
                "",
                "store",
                "svc-",
                "svc-Store",
                "svc-1store",
                "svc-store-",
                "svc-store--sync",
                "svc_store",
                "svc-" + "a".repeat(97),
            )

        invalid.forEach { clientId ->
            val response = registerResponse(token, clientId)
            assertEquals(400, response.status, "clientId=$clientId")
            assertEquals("VALIDATION_FAILED", json(response)["code"])
        }
        assertEquals(emptyList(), systemPrincipalIds())
    }

    @Test
    fun `CLI-01 서비스명은 하이픈으로 단어를 나눌 수 있고 컬럼 길이까지 받음`() {
        val token = adminToken(admin())
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)

        listOf("svc-s$suffix-sync-2", "svc-a$suffix" + "b".repeat(100 - 13)).forEach { clientId ->
            val response = registerResponse(token, clientId)
            assertEquals(201, response.status, "clientId=$clientId")
            track(json(response))
        }
    }

    @Test
    fun `이름이 없거나 비었거나 길거나 role 형식이 틀리면 400 VALIDATION_FAILED`() {
        val token = adminToken(admin())
        val bodies =
            listOf(
                mapOf("clientId" to newClientId()),
                mapOf("clientId" to newClientId(), "name" to "  "),
                mapOf("clientId" to newClientId(), "name" to "a".repeat(101)),
                mapOf("clientId" to newClientId(), "name" to "Store", "roles" to listOf("store")),
                mapOf("clientId" to newClientId(), "name" to "Store", "roles" to listOf(null)),
            )

        bodies.forEach { body ->
            val response = post(SYSTEM_CLIENTS, token, body)
            assertEquals(400, response.status, "body=$body")
            assertEquals("VALIDATION_FAILED", json(response)["code"])
        }
        assertEquals(emptyList(), systemPrincipalIds())
    }

    @Test
    fun `GOV-06 system role을 지정하면 403 FORBIDDEN이고 아무것도 만들지 않음`() {
        val adminToken = adminToken(admin())
        val ownerToken = ownerToken(owner())
        val general = employees.newRoleCode("store").also(employees::defineRole)

        val responses =
            listOf(
                registerResponse(ownerToken, newClientId(), roles = listOf("auth:admin")),
                registerResponse(ownerToken, newClientId(), roles = listOf("auth:owner")),
                registerResponse(adminToken, newClientId(), roles = listOf(general.value, "auth:admin")),
            )

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("FORBIDDEN", json(it)["code"])
        }
        assertEquals(emptyList(), systemPrincipalIds())
        assertEquals(emptyList(), allAuditActions())
    }

    @Test
    fun `없는 role을 지정하면 404 NOT_FOUND이고 system role 검사보다 먼저 판단`() {
        val token = adminToken(admin())
        val undefined = employees.newRoleCode("store").value

        listOf(listOf(undefined), listOf(undefined, "auth:admin")).forEach { roles ->
            val response = registerResponse(token, newClientId(), roles = roles)
            assertEquals(404, response.status)
            assertEquals("NOT_FOUND", json(response)["code"])
        }
        assertEquals(emptyList(), systemPrincipalIds())
    }

    @Test
    fun `GOV-15 system role 지정은 clientId 중복보다 먼저 판단`() {
        val token = ownerToken(owner())
        val existing = register(token, newClientId())

        val response = registerResponse(token, existing.clientId, roles = listOf("auth:admin"))

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
    }

    @Test
    fun `CLI-04 비활성화한 client의 clientId로 다시 등록할 수 있고 이전 secret으로는 토큰을 받지 못함`() {
        val token = adminToken(admin())
        val old = register(token, newClientId())
        val deactivated = post("/admin/principals/${old.principalId}/deactivate", token, mapOf("reason" to "서비스 교체"))
        assertEquals(204, deactivated.status)

        val renewed = register(token, old.clientId)

        assertNotEquals(old.principalId, renewed.principalId)
        assertEquals(401, requestSystemToken(old.clientId, old.secret).status)
        assertEquals(200, requestSystemToken(renewed.clientId, renewed.secret).status)
    }

    // 목록

    @Test
    fun `목록은 client마다 상태, role, secret 발급 시각을 담고 secret과 해시는 담지 않음`() {
        val token = adminToken(admin())
        val role = employees.newRoleCode("catalog").also(employees::defineRole)
        val first = register(token, newClientId(), name = "Catalog 서비스", roles = listOf(role.value))
        val second = register(token, newClientId())
        assertEquals(204, post("/admin/principals/${second.principalId}/suspend", token, mapOf("reason" to "점검")).status)

        val response = list(token)

        assertEquals(200, response.status)
        @Suppress("UNCHECKED_CAST")
        val items = (json(response)["items"] as List<Map<String, Any?>>).associateBy { it["principalId"] }
        val firstItem = checkNotNull(items[first.principalId.toString()])
        assertEquals(
            setOf("principalId", "clientId", "name", "status", "roles", "secretRotatedAt", "createdAt"),
            firstItem.keys,
        )
        assertEquals(first.clientId, firstItem["clientId"])
        assertEquals("Catalog 서비스", firstItem["name"])
        assertEquals("ACTIVE", firstItem["status"])
        assertEquals(listOf(role.value), firstItem["roles"])
        val row = clientRow(first.principalId)
        assertEquals(row[SystemClientTable.secretRotatedAt], Instant.parse(firstItem["secretRotatedAt"] as String))
        assertEquals("SUSPENDED", checkNotNull(items[second.principalId.toString()])["status"])
        val content = response.contentAsString
        listOf(first, second).forEach {
            assertFalse(it.secret in content)
            assertFalse(SecretHash.of(it.secret).hex in content)
        }
    }

    @Test
    fun `목록에는 비활성화한 client도 deleted-id와 DEACTIVATED로 나옴`() {
        val token = adminToken(admin())
        val client = register(token, newClientId())
        post("/admin/principals/${client.principalId}/deactivate", token, mapOf("reason" to "서비스 종료"))

        @Suppress("UNCHECKED_CAST")
        val items = json(list(token))["items"] as List<Map<String, Any?>>

        val item = items.single { it["principalId"] == client.principalId.toString() }
        assertEquals("deleted-${client.principalId}", item["clientId"])
        assertEquals("DEACTIVATED", item["status"])
        assertEquals(emptyList<String>(), item["roles"])
    }

    // secret 재발급

    @Test
    fun `CLI-03 재발급하면 새 secret을 한 번 돌려주고 이전 secret은 즉시 invalid_client`() {
        val token = adminToken(admin())
        val client = register(token, newClientId())
        val issuedBefore = requestSystemToken(client.clientId, client.secret)
        assertEquals(200, issuedBefore.status)

        val response = rotate(client.principalId, token)

        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL))
        val body = json(response)
        assertEquals(setOf("clientSecret"), body.keys)
        val newSecret = body["clientSecret"] as String
        assertNotEquals(client.secret, newSecret)
        val old = requestSystemToken(client.clientId, client.secret)
        assertEquals(401, old.status)
        assertEquals("invalid_client", json(old)["error"])
        assertEquals(200, requestSystemToken(client.clientId, newSecret).status)
        assertEquals(SecretHash.of(newSecret).hex, clientRow(client.principalId)[SystemClientTable.clientSecretHash])
    }

    @Test
    fun `재발급은 secret 발급 시각을 바꾸고 CLIENT_SECRET_ROTATED를 남김`() {
        val admin = admin()
        val client = register(adminToken(admin), newClientId())
        val registeredAt = clientRow(client.principalId)[SystemClientTable.secretRotatedAt]

        rotate(client.principalId, adminToken(admin))

        val row = clientRow(client.principalId)
        assertFalse(row[SystemClientTable.secretRotatedAt].isBefore(registeredAt))
        assertEquals(registeredAt, row[SystemClientTable.createdAt])
        val audit = audits(client.principalId).last()
        assertEquals("CLIENT_SECRET_ROTATED", audit.action)
        assertEquals(admin.id, audit.actorId)
        assertEquals(emptyMap(), audit.detail.orEmpty())
    }

    @Test
    fun `owner도 재발급할 수 있음`() {
        val client = register(adminToken(admin()), newClientId())

        assertEquals(200, rotate(client.principalId, ownerToken(owner())).status)
    }

    @Test
    fun `없는 principal이나 system client가 아닌 principal의 재발급은 404 NOT_FOUND`() {
        val token = adminToken(admin())
        val employee = employees.create()
        val owner = owner()

        listOf(UUID.randomUUID(), employee.id, owner.id).forEach { id ->
            val response = rotate(id, token)
            assertEquals(404, response.status)
            assertEquals("NOT_FOUND", json(response)["code"])
        }
    }

    @Test
    fun `ACTIVE가 아닌 client의 재발급은 409 INVALID_STATE이고 secret을 바꾸지 않음`() {
        val token = adminToken(admin())
        val suspended = register(token, newClientId())
        assertEquals(204, post("/admin/principals/${suspended.principalId}/suspend", token, mapOf("reason" to "점검")).status)
        val deactivated = register(token, newClientId())
        assertEquals(204, post("/admin/principals/${deactivated.principalId}/deactivate", token, mapOf("reason" to "종료")).status)
        val suspendedHash = clientRow(suspended.principalId)[SystemClientTable.clientSecretHash]

        listOf(suspended, deactivated).forEach {
            val response = rotate(it.principalId, token)
            assertEquals(409, response.status)
            assertEquals("INVALID_STATE", json(response)["code"])
            assertFalse("CLIENT_SECRET_ROTATED" in audits(it.principalId).map { audit -> audit.action })
        }
        assertEquals(suspendedHash, clientRow(suspended.principalId)[SystemClientTable.clientSecretHash])
        assertEquals(null, clientRow(deactivated.principalId)[SystemClientTable.clientSecretHash])
    }

    // 민감정보 (SEC-03)

    @Test
    fun `SEC-03 등록과 재발급의 secret은 로그에 남지 않음`(output: CapturedOutput) {
        val token = adminToken(admin())
        val client = register(token, newClientId())
        val rotated = json(rotate(client.principalId, token))["clientSecret"] as String
        list(token)

        assertFalse(client.secret in output.all)
        assertFalse(rotated in output.all)
        assertFalse(SecretHash.of(client.secret).hex in output.all)
    }

    private fun admin(): CreatedEmployee = employees.create().also(employees::makeAdmin)

    private fun owner(): CreatedEmployee = employees.create().also(employees::makeOwner)

    private fun adminToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:admin"))

    private fun ownerToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:owner"))

    /** 테스트마다 다른 CLI-01 형식의 client_id. */
    private fun newClientId(): String = "svc-it${UUID.randomUUID().toString().replace("-", "").take(12)}"

    /** API로 등록하고 성공했는지 확인합니다. */
    private fun register(
        token: String,
        clientId: String,
        name: String = "통합 테스트",
        roles: List<String>? = null,
    ): RegisteredClient {
        val response = registerResponse(token, clientId, name, roles)
        check(response.status == 201) { "등록 실패: ${response.status} ${response.contentAsString}" }
        return track(json(response))
    }

    private fun registerResponse(
        token: String,
        clientId: String?,
        name: String = "통합 테스트",
        roles: List<String>? = null,
    ): MockHttpServletResponse {
        val body =
            buildMap<String, Any?> {
                put("clientId", clientId)
                put("name", name)
                roles?.let { put("roles", it) }
            }
        return post(SYSTEM_CLIENTS, token, body).also { if (it.status == 201) track(json(it)) }
    }

    /** 등록 응답의 client를 정리 대상으로 등록합니다. 같은 응답을 여러 번 넘겨도 한 번만 등록합니다. */
    private fun track(body: Map<String, Any?>): RegisteredClient {
        val principalId = UUID.fromString(body["principalId"] as String)
        if (principalId !in clients) {
            clients += principalId
            employees.track(principalId)
        }
        return RegisteredClient(principalId, body["clientId"] as String, body["clientSecret"] as String)
    }

    private fun rotate(
        principalId: UUID,
        token: String,
    ): MockHttpServletResponse = post("$SYSTEM_CLIENTS/$principalId/secret", token, null)

    private fun list(token: String?): MockHttpServletResponse =
        mockMvc
            .get(SYSTEM_CLIENTS) {
                token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
            }.andReturn()
            .response

    private fun post(
        path: String,
        token: String?,
        body: Map<String, Any?>?,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(it)
                }
            }.andReturn()
            .response

    private fun requestSystemToken(
        clientId: String,
        secret: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/token") {
                header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray()))
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = "grant_type=client_credentials"
            }.andReturn()
            .response

    /** 서명 검증 없이 payload만 읽습니다. 서명 검증은 서비스 토큰 발급 테스트가 확인합니다. */
    @Suppress("UNCHECKED_CAST")
    private fun jwtClaims(token: String): Map<String, Any?> =
        jsonMapper.readValue(Base64.getUrlDecoder().decode(token.split(".")[1]), Map::class.java) as Map<String, Any?>

    private fun clientRow(principalId: UUID) =
        employees.inTransaction {
            SystemClientTable.selectAll().where { SystemClientTable.principalId eq principalId }.single()
        }

    /** DB에 있는 system principal (이 테스트 클래스 밖에서 만든 것은 정리되므로 이 테스트가 만든 것만 남음). */
    private fun systemPrincipalIds(): List<UUID> =
        employees.inTransaction {
            PrincipalTable
                .selectAll()
                .where { PrincipalTable.type eq PrincipalType.SYSTEM.name }
                .map { it[PrincipalTable.id] }
        }

    private fun allAuditActions(): List<String> =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .orderBy(AuditLogTable.id)
                .map { it[AuditLogTable.action] }
        }

    private fun audits(principalId: UUID): List<AuditRow> =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .where { (AuditLogTable.targetType eq "PRINCIPAL") and (AuditLogTable.targetId eq principalId.toString()) }
                .orderBy(AuditLogTable.id)
                .map { AuditRow(it[AuditLogTable.action], it[AuditLogTable.actorId], it[AuditLogTable.detail]) }
        }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private data class AuditRow(
        val action: String,
        val actorId: UUID?,
        val detail: Map<String, Any?>?,
    )

    private data class RegisteredClient(
        val principalId: UUID,
        val clientId: String,
        val secret: String,
    ) {
        override fun toString(): String = "RegisteredClient(principalId=$principalId, clientId=$clientId)"
    }

    private companion object {
        const val SYSTEM_CLIENTS = "/admin/system-clients"
    }
}
