package com.dozycoffee.auth.server

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.SystemClientTable
import com.dozycoffee.auth.server.application.port.inbound.system.BootstrapOwnerCommand
import com.dozycoffee.auth.server.application.port.inbound.system.BootstrapOwnerOutcome
import com.dozycoffee.auth.server.application.port.inbound.system.BootstrapOwnerUseCase
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadOwnerPort
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.PasswordResetMail
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.token.IssuerBaseUri
import com.dozycoffee.auth.server.support.RecordingMailConfig
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.starter.DozyAuthProperties
import com.dozycoffee.auth.starter.DozyAuthenticationToken
import com.dozycoffee.auth.starter.DozyJwtAuthenticationConverter
import com.dozycoffee.auth.starter.DozyJwtDecoders
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import jakarta.servlet.http.Cookie
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.HttpCookie
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 전체 흐름 통합 테스트. 지금까지 만든 API를 실제 순서대로 이어서, 콘솔로 계정과 권한을 관리할 수 있는지 확인합니다.
 *
 * 각 API의 세부 동작은 API별 테스트가 다루므로, 여기서는 API 사이의 연결(발급한 토큰, 메일의 링크 토큰, 세션, 감사 기록이 다음 단계에서
 * 그대로 쓰이는지)을 봅니다. 단계는 앞 단계가 만든 계정과 토큰을 이어 쓰므로 순서대로 실행하고, 앞 단계가 실패하면 뒤 단계는 건너뜁니다.
 *
 * - 요청은 모두 MockMvc로 실제 엔드포인트를 거칩니다. owner 부트스트랩만 기동 리스너가 부르는 UseCase를 직접 부릅니다
 *   (test 프로필은 기동 부트스트랩을 끔, configuration.md §4).
 * - 링크 토큰은 DB가 아니라 보낸 메일([RecordingMailSender])에서 꺼냅니다.
 * - 서비스 쪽 검증은 서비스가 쓰는 스타터의 디코더([DozyJwtDecoders])와 권한 변환([DozyJwtAuthenticationConverter])으로 합니다.
 *   공개키는 JWKS API 응답입니다.
 * - 실제 DB에 커밋하므로 만든 계정, role, system client, 감사 로그는 끝나면 지웁니다. 요청 제한 카운터는 단계마다 비워집니다
 *   ([com.dozycoffee.auth.server.support.RateLimitResetListener]).
 *
 * 경로, 에러 code, 감사 action, claim 이름, 권한 이름 형식은 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, RecordingMailConfig::class)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ExtendWith(OutputCaptureExtension::class, FullFlowIntegrationTest.SkipAfterFailedStep::class)
class FullFlowIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var bootstrapOwner: BootstrapOwnerUseCase

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var mails: RecordingMailSender

    @Autowired
    lateinit var issuerBaseUri: IssuerBaseUri

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var loadOwner: LoadOwnerPort

    private val jsonMapper = JsonMapper.builder().build()

    /** 흐름이 시작한 시각. 감사 로그를 이 흐름의 기록으로 좁힐 때 씁니다. */
    private lateinit var startedAt: Instant

    private lateinit var owner: Member
    private lateinit var ownerSession: Session
    private lateinit var admin: Member
    private lateinit var adminSession: Session
    private lateinit var staff: Member
    private lateinit var staffSession: Session
    private lateinit var wmsRole: String
    private lateinit var client: Client

    /** 단계마다 남아야 하는 감사 기록 (기록 순서). 8단계에서 감사 로그 조회 결과와 비교합니다. */
    private val expectedAudits = mutableListOf<Audit>()

    /** 로그와 출력에 남으면 안 되는 값 (SEC-03): 비밀번호, 토큰 원문, 링크 토큰, client secret. */
    private val secrets = mutableSetOf<String>()

    private val createdRoleIds = mutableListOf<Long>()
    private val createdClientIds = mutableListOf<UUID>()

    @BeforeAll
    fun setUp() {
        mails.sent.clear()
        startedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
    }

    @AfterAll
    fun cleanUp() {
        // 부트스트랩한 owner는 어느 단계에서 실패해도 지웁니다. 남으면 부트스트랩 테스트가 owner 없는 상태에서 시작하지 못합니다
        employees.inTransaction { listOfNotNull(loadOwner.findOwnerId()) }.forEach(employees::track)
        employees.inTransaction {
            if (createdClientIds.isNotEmpty()) SystemClientTable.deleteWhere { SystemClientTable.principalId inList createdClientIds }
            // role 정의는 만든 사람(principal)을 참조하므로 principal보다 먼저 지웁니다
            if (createdRoleIds.isNotEmpty()) {
                PrincipalRoleTable.deleteWhere { PrincipalRoleTable.roleId inList createdRoleIds }
                RoleTable.deleteWhere { RoleTable.id inList createdRoleIds }
            }
        }
        employees.cleanUp()
    }

    /** SEC-03 단계마다 그 단계의 출력에 지금까지 쓴 비밀값이 없는지 확인합니다. 출력은 단계(메서드)마다 따로 잡힙니다. */
    @AfterEach
    fun noSecretsInOutput(output: CapturedOutput) {
        val leaked = secrets.count { it in output.all }
        assertEquals(0, leaked, "출력에 남은 비밀값 수") // 값 자체는 메시지에 쓰지 않음
    }

    @Test
    @Order(1)
    fun `1 부트스트랩한 owner는 메일의 초대를 수락하고 로그인함`() {
        val email = newEmail("owner")

        val result = bootstrapOwner.bootstrap(BootstrapOwnerCommand(Email(email)))

        assertEquals(BootstrapOwnerOutcome.OWNER_INVITED, result.outcome)
        acceptResponse(invitationMailTo(email), OWNER_PASSWORD).expectStatus(204)
        ownerSession = refreshed(loginResponse(email, OWNER_PASSWORD))
        val claims = claims(ownerSession.accessToken)
        owner = Member(UUID.fromString(claims["principalId"] as String), email, OWNER_PASSWORD)
        assertEquals(listOf("auth:owner"), claims["roles"])
        expectAudit("EMPLOYEE_INVITED", null, owner.id) // GOV-11 부트스트랩은 행위자 없음
        expectAudit("ROLE_GRANTED", null, owner.id)
        expectAudit("INVITATION_ACCEPTED", owner.id, owner.id)
        expectAudit("LOGIN_SUCCEEDED", owner.id, owner.id)
    }

    @Test
    @Order(2)
    fun `2 owner가 초대한 직원을 admin으로 임명하면 그 직원은 admin으로 로그인함`() {
        val email = newEmail("admin")

        admin = invite(ownerSession, owner, email, "박관리", roles = emptyList(), password = ADMIN_PASSWORD)
        accept(invitationMailTo(email), admin)
        grantRoles(ownerSession, owner, admin, "auth:admin")
        adminSession = login(admin)

        assertEquals(listOf("auth:admin"), claims(adminSession.accessToken)["roles"])
    }

    @Test
    @Order(3)
    fun `3 admin이 정의한 wms role로 초대한 직원의 access token을 wms 서비스가 받고 그 role로 인가함`() {
        val code = "flow_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        wmsRole = "wms:$code"
        defineRole(adminSession, admin, "wms", code)
        val email = newEmail("staff")

        staff = invite(adminSession, admin, email, "이직원", roles = listOf(wmsRole), password = STAFF_PASSWORD)
        accept(invitationMailTo(email), staff)
        staffSession = login(staff)

        val authentication = wmsService(staffSession.accessToken)
        assertEquals(PrincipalKey(PrincipalType.EMPLOYEE, staff.id), authentication.authenticatedPrincipal.key)
        assertEquals(setOf("ROLE_$code"), authentication.authorities.map { it.authority }.toSet())
    }

    @Test
    @Order(4)
    fun `4 admin이 role을 회수하면 갱신한 토큰에 role이 없어 wms 서비스가 받지 않음`() {
        mockMvc.delete("/admin/principals/${staff.id}/roles/$wmsRole") { bearer(adminSession) }.andExpect { status { isNoContent() } }
        expectAudit("ROLE_REVOKED", admin.id, staff.id)

        staffSession = refresh(staffSession).let(::refreshed)

        assertEquals(emptyList<Any?>(), claims(staffSession.accessToken)["roles"])
        assertFailsWith<JwtException> { wmsService(staffSession.accessToken) }
    }

    @Test
    @Order(5)
    fun `5 비밀번호를 바꾸면 다른 세션은 갱신이 거부되고, 재설정하면 이전 비밀번호로는 로그인할 수 없음`() {
        val otherSession = login(staff)

        // PWD-06 비밀번호 변경은 현재 세션만 남김
        val changed = secret(STAFF_CHANGED_PASSWORD)
        post(
            "/realms/internal/password/change",
            mapOf("currentPassword" to staff.password, "newPassword" to changed),
            staffSession,
        ).expectStatus(204)
        expectAudit("PASSWORD_CHANGED", staff.id, staff.id)
        refresh(otherSession).expectError(401, "SESSION_EXPIRED")
        staffSession = refresh(staffSession).let(::refreshed)
        staff = staff.copy(password = changed)

        // PWD-07 비밀번호 찾기·재설정은 모든 세션을 폐기
        post("/realms/internal/password/forgot", mapOf("email" to staff.email)).expectStatus(202)
        val reset = mails.sent.filterIsInstance<PasswordResetMail>().single { it.to == Email(staff.email) }
        val newPassword = secret(STAFF_RESET_PASSWORD)
        post("/realms/internal/password/reset", mapOf("token" to secret(reset.token.value), "newPassword" to newPassword))
            .expectStatus(204)
        expectAudit("PASSWORD_RESET", staff.id, staff.id)
        refresh(staffSession).expectError(401, "SESSION_EXPIRED")

        loginResponse(staff.email, staff.password).expectError(401, "INVALID_CREDENTIALS")
        expectAudit("LOGIN_FAILED", staff.id, staff.id)
        staff = staff.copy(password = newPassword)
        staffSession = login(staff)
    }

    @Test
    @Order(6)
    fun `6 정지한 직원은 갱신이 거부되고 해제 후 다시 로그인하며, 비활성화한 직원의 이메일로 다시 초대함`() {
        post("/admin/principals/${staff.id}/suspend", mapOf("reason" to "흐름 확인"), adminSession).expectStatus(204)
        expectAudit("ACCOUNT_SUSPENDED", admin.id, staff.id)
        refresh(staffSession).expectError(401, "SESSION_EXPIRED")

        post("/admin/principals/${staff.id}/reactivate", null, adminSession).expectStatus(204)
        expectAudit("ACCOUNT_REACTIVATED", admin.id, staff.id)
        staffSession = login(staff)

        post("/admin/principals/${staff.id}/deactivate", mapOf("reason" to "퇴사"), adminSession).expectStatus(204)
        expectAudit("ACCOUNT_DEACTIVATED", admin.id, staff.id)
        refresh(staffSession).expectError(401, "SESSION_EXPIRED")
        loginResponse(staff.email, staff.password).expectError(401, "INVALID_CREDENTIALS")
        expectAudit("LOGIN_FAILED", null, null) // ACC-04 이메일을 파기해 계정을 찾지 못함 (AUD-08)

        val rehired = invite(adminSession, admin, staff.email, "이직원", roles = emptyList(), password = REHIRED_PASSWORD)
        assertNotEquals(staff.id, rehired.id)
        accept(invitationMailTo(rehired.email), rehired)
        login(rehired)
    }

    @Test
    @Order(7)
    fun `7 admin이 등록한 system client는 secret으로 토큰을 받고, 재발급하면 이전 secret은 거부됨`(output: CapturedOutput) {
        val clientId = "svc-flow${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val registered =
            post("/admin/system-clients", mapOf("clientId" to clientId, "name" to "흐름 확인", "roles" to listOf(wmsRole)), adminSession)
        registered.expectStatus(201)
        val body = json(registered)
        client = Client(UUID.fromString(body["principalId"] as String), clientId, secret(body["clientSecret"] as String))
        createdClientIds += client.principalId
        employees.track(client.principalId)
        expectAudit("SYSTEM_CLIENT_REGISTERED", admin.id, client.principalId)
        expectAudit("ROLE_GRANTED", admin.id, client.principalId)

        val systemToken = systemToken(client.clientId, client.secret).let { json(it.expectStatus(200))["access_token"] as String }
        assertEquals(PrincipalKey(PrincipalType.SYSTEM, client.principalId), wmsService(secret(systemToken)).authenticatedPrincipal.key)

        val rotated = post("/admin/system-clients/${client.principalId}/secret", null, adminSession)
        val newSecret = secret(json(rotated.expectStatus(200))["clientSecret"] as String)
        expectAudit("CLIENT_SECRET_ROTATED", admin.id, client.principalId)

        systemToken(client.clientId, client.secret).let {
            assertEquals(401, it.status)
            assertEquals("invalid_client", json(it)["error"])
        }
        systemToken(client.clientId, newSecret).expectStatus(200).also { secret(json(it)["access_token"] as String) }
        // SEC-03 확인(noSecretsInOutput)이 실제 로그를 보고 있는지: 서비스 토큰 발급 로그는 client_id를 남김
        assertTrue(client.clientId in output.all, "발급 로그를 잡지 못함")
    }

    @Test
    @Order(8)
    fun `8 owner가 감사 로그를 조회하면 흐름의 관리 작업이 일어난 순서대로 남아 있음`() {
        val response =
            mockMvc
                .get("/admin/audit-logs") {
                    bearer(ownerSession)
                    param("from", startedAt.toString())
                    param("size", "100")
                }.andReturn()
                .response
                .expectStatus(200)

        @Suppress("UNCHECKED_CAST")
        val items = json(response)["items"] as List<Map<String, Any?>>
        // 최신순이므로 뒤집어 기록 순서로 비교합니다
        val recorded =
            items.reversed().map {
                Audit(it["action"] as String, (it["actorId"] as String?)?.let(UUID::fromString), it["targetId"] as String?)
            }
        assertEquals(expectedAudits, recorded)
    }

    // 흐름의 한 동작

    /** 관리자가 직원을 초대하고, 받은 principal id로 초대받은 직원을 돌려줍니다. */
    private fun invite(
        session: Session,
        manager: Member,
        email: String,
        name: String,
        roles: List<String>,
        password: String,
    ): Member {
        val response = post("/admin/employees", mapOf("email" to email, "name" to name, "roles" to roles), session).expectStatus(201)
        val member = Member(UUID.fromString(json(response)["principalId"] as String), email, password)
        employees.track(member.id)
        expectAudit("EMPLOYEE_INVITED", manager.id, member.id)
        if (roles.isNotEmpty()) expectAudit("ROLE_GRANTED", manager.id, member.id)
        return member
    }

    private fun accept(
        invitation: EmployeeInvitationMail,
        member: Member,
    ) {
        acceptResponse(invitation, member.password).expectStatus(204)
        expectAudit("INVITATION_ACCEPTED", member.id, member.id)
    }

    private fun acceptResponse(
        invitation: EmployeeInvitationMail,
        password: String,
    ): MockHttpServletResponse =
        post("/realms/internal/invitations/accept", mapOf("token" to secret(invitation.token.value), "password" to secret(password)))

    private fun grantRoles(
        session: Session,
        manager: Member,
        target: Member,
        vararg roles: String,
    ) {
        post("/admin/principals/${target.id}/roles", mapOf("roles" to roles.toList()), session).expectStatus(204)
        expectAudit("ROLE_GRANTED", manager.id, target.id)
    }

    private fun defineRole(
        session: Session,
        manager: Member,
        audience: String,
        code: String,
    ) {
        val response = post("/admin/roles", mapOf("audience" to audience, "code" to code, "name" to "흐름 확인"), session).expectStatus(201)
        val roleId = (json(response)["id"] as Number).toLong().also { createdRoleIds += it }
        expectedAudits += Audit("ROLE_DEFINED", manager.id, roleId.toString())
    }

    private fun login(member: Member): Session {
        val session = refreshed(loginResponse(member.email, member.password))
        expectAudit("LOGIN_SUCCEEDED", member.id, member.id)
        return session
    }

    private fun loginResponse(
        email: String,
        password: String,
    ): MockHttpServletResponse = post("/realms/internal/login", mapOf("email" to email, "password" to secret(password)))

    private fun refresh(session: Session): MockHttpServletResponse =
        mockMvc
            .post("/realms/internal/token/refresh") {
                cookie(Cookie("dozy_refresh", session.refreshToken))
                header("Origin", ALLOWED_ORIGIN)
            }.andReturn()
            .response

    /** 로그인·갱신 응답의 access token과 새 refresh 쿠키. */
    private fun refreshed(response: MockHttpServletResponse): Session {
        response.expectStatus(200)
        val refreshToken = HttpCookie.parse(checkNotNull(response.getHeader(HttpHeaders.SET_COOKIE))).single().value
        return Session(secret(json(response)["accessToken"] as String), secret(refreshToken))
    }

    private fun systemToken(
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

    // 서비스 쪽 검증

    /**
     * `wms` 서비스가 [accessToken]을 받았을 때의 인증 정보. 서비스와 같은 스타터의 디코더로 검증하고(서명, issuer, `aud`, 만료 등)
     * 권한으로 바꿉니다. 받지 않으면 [JwtException]입니다.
     */
    private fun wmsService(accessToken: String): DozyAuthenticationToken {
        val properties = DozyAuthProperties(audience = "wms", acceptedRealms = setOf(Realm.INTERNAL), issuerBaseUri = issuerBaseUri.value)
        val decoder: JwtDecoder = DozyJwtDecoders.create(properties, publishedKeys(), clock)
        val jwt: Jwt = decoder.decode(accessToken)
        return DozyJwtAuthenticationConverter(properties).convert(jwt) as DozyAuthenticationToken
    }

    private fun publishedKeys(): ImmutableJWKSet<SecurityContext> =
        ImmutableJWKSet(
            JWKSet.parse(
                mockMvc
                    .get("/.well-known/jwks.json")
                    .andReturn()
                    .response.contentAsString,
            ),
        )

    // 요청과 응답

    private fun post(
        path: String,
        body: Map<String, Any?>?,
        session: Session? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                session?.let { bearer(it) }
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = jsonMapper.writeValueAsString(it)
                }
            }.andReturn()
            .response

    private fun MockHttpServletRequestDsl.bearer(session: Session) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${session.accessToken}")
    }

    /** 응답 상태를 확인합니다. 실패 메시지에는 본문을 쓰지 않습니다 (토큰·secret이 담길 수 있음). */
    private fun MockHttpServletResponse.expectStatus(expected: Int): MockHttpServletResponse =
        apply { assertEquals(expected, status, "응답 상태") }

    private fun MockHttpServletResponse.expectError(
        status: Int,
        code: String,
    ) {
        expectStatus(status)
        assertEquals(code, json(this)["code"])
    }

    /** payload만 읽습니다. 서명 검증은 [wmsService]와 다른 테스트가 합니다. */
    @Suppress("UNCHECKED_CAST")
    private fun claims(token: String): Map<String, Any?> =
        jsonMapper.readValue(Base64.getUrlDecoder().decode(token.split('.')[1]), Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private fun invitationMailTo(email: String): EmployeeInvitationMail =
        mails.sent.filterIsInstance<EmployeeInvitationMail>().last { it.to == Email(email) }

    private fun expectAudit(
        action: String,
        actorId: UUID?,
        targetId: UUID?,
    ) {
        expectedAudits += Audit(action, actorId, targetId?.toString())
    }

    /** [value]를 비밀값으로 등록하고 그대로 돌려줍니다. */
    private fun secret(value: String): String = value.also { secrets += it }

    private fun newEmail(role: String): String = "flow-$role-${UUID.randomUUID()}@dozycoffee.test"

    private data class Member(
        val id: UUID,
        val email: String,
        val password: String,
    ) {
        override fun toString(): String = "Member(id=$id)"
    }

    private data class Session(
        val accessToken: String,
        val refreshToken: String,
    ) {
        override fun toString(): String = "Session"
    }

    private data class Client(
        val principalId: UUID,
        val clientId: String,
        val secret: String,
    ) {
        override fun toString(): String = "Client(principalId=$principalId, clientId=$clientId)"
    }

    /** 감사 기록의 action, 행위자, 대상 id. */
    private data class Audit(
        val action: String,
        val actorId: UUID?,
        val targetId: String?,
    )

    /** 앞 단계가 실패하면 뒤 단계를 건너뜁니다. 뒤 단계는 앞 단계가 만든 계정과 토큰을 이어 쓰기 때문입니다. */
    class SkipAfterFailedStep :
        TestWatcher,
        ExecutionCondition {
        override fun testFailed(
            context: ExtensionContext,
            cause: Throwable?,
        ) {
            store(context).put(FAILED_STEP, context.displayName)
        }

        override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
            val failed = store(context).get(FAILED_STEP, String::class.java) ?: return ConditionEvaluationResult.enabled("앞 단계 통과")
            return ConditionEvaluationResult.disabled("앞 단계 실패: $failed")
        }

        /** 테스트 클래스 단위 저장소. 메서드 단위 저장소는 메서드가 끝나면 사라집니다. */
        private fun store(context: ExtensionContext): ExtensionContext.Store {
            val classContext = if (context.testMethod.isPresent) context.parent.orElseThrow() else context
            return classContext.getStore(ExtensionContext.Namespace.create(SkipAfterFailedStep::class.java))
        }

        private companion object {
            const val FAILED_STEP = "failedStep"
        }
    }

    private companion object {
        /** test 프로필의 CORS 허용 origin (`application-test.yaml`). */
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
        const val OWNER_PASSWORD = "owner-horse-battery-staple"
        const val ADMIN_PASSWORD = "admin-horse-battery-staple"
        const val STAFF_PASSWORD = "staff-horse-battery-staple"
        const val STAFF_CHANGED_PASSWORD = "staff-changed-battery-staple"
        const val STAFF_RESET_PASSWORD = "staff-reset-battery-staple"
        const val REHIRED_PASSWORD = "rehired-horse-battery-staple"
    }
}
