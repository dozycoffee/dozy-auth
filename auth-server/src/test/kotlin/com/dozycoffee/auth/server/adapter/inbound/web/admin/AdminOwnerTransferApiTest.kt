package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RefreshSessionTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.VerificationTable
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferCompletedMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferRequestMail
import com.dozycoffee.auth.server.application.port.outbound.verification.IssueVerificationPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.authorization.SystemRoles
import com.dozycoffee.auth.server.domain.verification.NewVerification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import com.dozycoffee.auth.server.support.RecordingMailConfig
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestAccessTokens
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.Companion.PASSWORD
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.Cookie
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.HttpCookie
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * owner 양도 요청·수락·취소 (api/admin.md §7, GOV-09, GOV-10, GOV-14, VER-01, VER-04, SES-06, AUD-03, AUD-08, SEC-03,
 * api/conventions.md §2). 실제 DB에 커밋하며 확인하고, 메일은 커밋 후 발송을 거쳐 [RecordingMailSender]에 기록합니다.
 * 만료는 유효 시간만큼 전에 발급한 양도로 확인합니다. 시계를 바꾸는 설정을 더하면 스프링 컨텍스트가 하나 더 생겨(DB 연결 수 증가)
 * 다른 관리 API 테스트와 같은 설정을 씁니다.
 *
 * owner는 DB 전체에서 한 명이므로(GOV-10) 테스트마다 owner를 만들고 [TestEmployees.cleanUp]으로 지웁니다.
 * 에러 code, 감사 action과 detail, 세션 폐기 사유는 명세의 문자열 그대로 기대값으로 씁니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, TestAccessTokens::class, RecordingMailConfig::class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
class AdminOwnerTransferApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var tokens: TestAccessTokens

    @Autowired
    lateinit var mails: RecordingMailSender

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var loadPrincipalRoles: LoadPrincipalRolesPort

    @Autowired
    lateinit var loadRole: LoadRolePort

    @Autowired
    lateinit var lockAccount: LockAccountPort

    @Autowired
    lateinit var issueVerification: IssueVerificationPort

    private val jsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() = mails.sent.clear()

    @AfterEach
    fun cleanUp() = employees.cleanUp()

    // 요청

    @Test
    fun `GOV-09 owner가 요청하면 202와 만료 시각을 주고 대상에게 OWNER_TRANSFER를 발급해 수락 메일을 보냄`() {
        val owner = owner()
        val target = employees.create(name = "이서연")
        val before = clock.instant()

        val response = request(ownerToken(owner), target.id)

        assertEquals(202, response.status)
        val expiresAt = Instant.parse(json(response)["expiresAt"] as String)
        assertTrue(expiresAt in before.plus(AuthPolicy.OWNER_TRANSFER_TTL)..clock.instant().plus(AuthPolicy.OWNER_TRANSFER_TTL))
        assertEquals(expiresAt, verificationRows(target.id).single()[VerificationTable.expiresAt])
        val verification = verificationRows(target.id).single()
        assertEquals("OWNER_TRANSFER", verification[VerificationTable.purpose])
        assertEquals(target.email, verification[VerificationTable.target])
        assertEquals(mapOf("requestedBy" to owner.id.toString()), verification[VerificationTable.payload])
        val mail = mails.sent.filterIsInstance<OwnerTransferRequestMail>().single()
        assertEquals(Email(target.email), mail.to)
        assertEquals("이서연", mail.name)
        assertEquals(verification[VerificationTable.tokenHash], SecretHash.of(mail.token.value).hex)
        assertEquals(listOf(AuditRow("OWNER_TRANSFER_REQUESTED", owner.id, null)), audits(target.id))
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(owner.id))
    }

    @Test
    fun `GOV-09 진행 중인 양도가 있으면 다른 대상으로 요청해도 409 INVALID_STATE`() {
        val owner = owner()
        val first = employees.create()
        val second = employees.create()
        request(ownerToken(owner), first.id)

        val response = request(ownerToken(owner), second.id)

        assertEquals(409, response.status)
        assertEquals("INVALID_STATE", json(response)["code"])
        assertTrue(verificationRows(second.id).isEmpty())
        assertEquals(1, mails.sent.filterIsInstance<OwnerTransferRequestMail>().size)
    }

    @Test
    fun `GOV-09 대상이 자기 자신이거나 ACTIVE 직원이 아니면 409 INVALID_STATE`() {
        val owner = owner()
        val pending = employees.create(status = AccountStatus.PENDING, password = null)
        val suspended = employees.create(status = AccountStatus.SUSPENDED)
        val deactivated = employees.create(status = AccountStatus.DEACTIVATED)
        val system = systemPrincipal()

        listOf(owner.id, pending.id, suspended.id, deactivated.id, system).forEach {
            val response = request(ownerToken(owner), it)
            assertEquals(409, response.status, "대상 $it")
            assertEquals("INVALID_STATE", json(response)["code"])
        }
        assertTrue(liveTransfers().isEmpty())
        assertTrue(mails.sent.isEmpty())
    }

    @Test
    fun `대상 principal이 없으면 404 NOT_FOUND`() {
        val response = request(ownerToken(owner()), UUID.randomUUID())

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", json(response)["code"])
    }

    @Test
    fun `GOV-09 동시에 두 요청이 와도 진행 중인 양도는 하나만 생김`() {
        val owner = owner()
        val first = employees.create()
        val second = employees.create()
        val token = ownerToken(owner)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)

        // owner 행을 잠근 채 두 요청을 보내 둘 다 잠금을 기다리게 한 뒤 풀어 줍니다
        val holder =
            CompletableFuture.runAsync {
                employees.inTransaction {
                    lockAccount.lockAccountById(owner.id)
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        locked.await(WAIT_SECONDS, TimeUnit.SECONDS)
        val requests = listOf(first, second).map { CompletableFuture.supplyAsync { request(token, it.id) } }
        awaitLockWaiters(2)
        release.countDown()
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS)

        val statuses = requests.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS).status }.sorted()
        assertEquals(listOf(202, 409), statuses)
        assertEquals(1, liveTransfers().size)
    }

    // 인가 (GOV-14)

    @Test
    fun `GOV-14 토큰에 auth owner가 없으면 요청과 취소는 403 FORBIDDEN`() {
        owner()
        val admin = employees.create().also(employees::makeAdmin)
        val target = employees.create()
        val token = tokens.issue(admin.key, roles = listOf("auth:admin"))

        val responses = listOf(request(token, target.id), cancel(token))

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("FORBIDDEN", json(it)["code"])
        }
        assertTrue(liveTransfers().isEmpty())
    }

    @Test
    fun `GOV-14 토큰에 auth owner가 있어도 DB에서 owner가 아니면 요청과 취소는 403 FORBIDDEN`() {
        val owner = owner()
        val former = employees.create()
        val target = employees.create()
        request(ownerToken(owner), target.id)

        val responses = listOf(request(ownerToken(former), employees.create().id), cancel(ownerToken(former)))

        responses.forEach {
            assertEquals(403, it.status)
            assertEquals("FORBIDDEN", json(it)["code"])
        }
        assertEquals(listOf(target.id), liveTransfers().map { it[VerificationTable.principalId] })
    }

    @Test
    fun `토큰이 없으면 세 API 모두 401 UNAUTHENTICATED`() {
        val responses = listOf(request(null, UUID.randomUUID()), accept(null, "some-token"), cancel(null))

        responses.forEach {
            assertEquals(401, it.status)
            assertEquals("UNAUTHENTICATED", json(it)["code"])
        }
    }

    // 수락

    @Test
    fun `GOV-09 수락하면 한 트랜잭션에서 owner role을 대상에게 옮기고 기존 owner의 모든 세션을 OWNER_TRANSFERRED로 폐기`() {
        val owner = owner()
        val target = employees.create(name = "이서연")
        val ownerSessions = List(2) { refreshTokenOf(login(owner.email)) }
        val targetLogin = login(target.email)
        val transferToken = requestTransfer(owner, target)

        // 대상은 auth role이 없어 aud가 빈 로그인 토큰으로 수락합니다 (api/conventions.md §2)
        val response = accept(accessTokenOf(targetLogin), transferToken)

        assertEquals(204, response.status)
        assertEquals(emptySet(), rolesOf(owner.id))
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(target.id))
        val grant = ownerGrant()
        assertEquals(target.id, grant[PrincipalRoleTable.principalId])
        assertEquals(owner.id, grant[PrincipalRoleTable.grantedBy])
        ownerSessions.forEach { assertEquals("SESSION_EXPIRED", json(refresh(it))["code"]) }
        assertEquals(listOf("OWNER_TRANSFERRED", "OWNER_TRANSFERRED"), revokeReasons(owner.id))
        assertEquals(listOf(null), revokeReasons(target.id))
        val consumedAt = assertNotNull(verificationRows(target.id).single()[VerificationTable.consumedAt])
        assertEquals(AuditRow("OWNER_TRANSFERRED", target.id, mapOf("revokedSessions" to 2)), audits(owner.id).single())
        val mail = mails.sent.filterIsInstance<OwnerTransferCompletedMail>().single()
        assertEquals(Email(owner.email), mail.to)
        assertEquals("이서연", mail.newOwnerName)
        assertEquals(consumedAt, mail.transferredAt)
    }

    @Test
    fun `GOV-09 기존 owner의 살아 있는 세션이 없으면 감사 로그에 revokedSessions를 남기지 않음`() {
        val owner = owner()
        val target = employees.create()
        val transferToken = requestTransfer(owner, target)

        assertEquals(204, accept(tokens.issue(target.key), transferToken).status)

        assertEquals(AuditRow("OWNER_TRANSFERRED", target.id, null), audits(owner.id).single())
    }

    @Test
    fun `GOV-14 새 owner는 토큰 갱신 뒤 owner API를 쓰고 이전 owner의 남은 토큰은 DB role로 거부됨`() {
        val owner = owner()
        val target = employees.create()
        val previousOwnerToken = ownerToken(owner)
        val targetRefresh = refreshTokenOf(login(target.email))
        val transferToken = requestTransfer(owner, target)
        assertEquals(204, accept(tokens.issue(target.key), transferToken).status)

        val refreshed = refresh(targetRefresh)

        assertEquals(200, refreshed.status)
        val newOwnerToken = json(refreshed)["accessToken"] as String
        assertEquals(listOf("auth:owner"), SignedJWT.parse(newOwnerToken).jwtClaimsSet.getStringListClaim("roles"))
        assertEquals(200, auditLogs(newOwnerToken).status)
        assertEquals(403, auditLogs(previousOwnerToken).status)
        assertEquals(403, request(previousOwnerToken, employees.create().id).status)
    }

    @Test
    fun `GOV-09 양도 대상이 아닌 직원이 수락하면 403 FORBIDDEN이고 대상은 그 링크로 수락할 수 있음`() {
        val owner = owner()
        val target = employees.create()
        val other = employees.create()
        val transferToken = requestTransfer(owner, target)

        val response = accept(tokens.issue(other.key), transferToken)

        assertEquals(403, response.status)
        assertEquals("FORBIDDEN", json(response)["code"])
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(owner.id))
        assertNull(verificationRows(target.id).single()[VerificationTable.consumedAt])
        assertEquals(204, accept(tokens.issue(target.key), transferToken).status)
    }

    @Test
    fun `VER-04 이미 사용한 링크로 다시 수락하면 410 VERIFICATION_EXPIRED`() {
        val owner = owner()
        val target = employees.create()
        val transferToken = requestTransfer(owner, target)
        assertEquals(204, accept(tokens.issue(target.key), transferToken).status)

        val response = accept(tokens.issue(target.key), transferToken)

        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
        assertEquals(listOf("OWNER_TRANSFERRED"), audits(owner.id).map { it.action })
    }

    @Test
    fun `VER-04 없는 토큰이나 다른 목적의 토큰으로 수락하면 410 VERIFICATION_EXPIRED`() {
        val owner = owner()
        val target = employees.create()
        val resetToken =
            employees.inTransaction {
                val issued = NewVerification.issue(target.id, VerificationPurpose.PASSWORD_RESET, Email(target.email), clock.instant())
                issueVerification.issue(issued.verification)
                issued.token.value
            }

        listOf("unknown-token", resetToken).forEach {
            val response = accept(tokens.issue(target.key), it)
            assertEquals(410, response.status)
            assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
        }
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(owner.id))
    }

    @Test
    fun `VER-01 유효 시간이 지난 양도는 수락이 410이고 진행 중인 양도가 아니라 취소는 404, 새 요청은 202`() {
        val owner = owner()
        val target = employees.create()
        val transferToken = issueTransfer(owner, target, issuedAt = clock.instant().minus(AuthPolicy.OWNER_TRANSFER_TTL))

        val accepted = accept(tokens.issue(target.key), transferToken)
        assertEquals(410, accepted.status)
        assertEquals("VERIFICATION_EXPIRED", json(accepted)["code"])
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(owner.id))
        assertEquals(404, cancel(ownerToken(owner)).status)
        assertEquals(202, request(ownerToken(owner), employees.create().id).status)
    }

    @Test
    fun `VER-01 유효 시간이 남은 양도는 수락됨`() {
        val owner = owner()
        val target = employees.create()
        val issuedAt = clock.instant().minus(AuthPolicy.OWNER_TRANSFER_TTL).plus(Duration.ofMinutes(1))
        val transferToken = issueTransfer(owner, target, issuedAt)

        assertEquals(204, accept(tokens.issue(target.key), transferToken).status)
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(target.id))
    }

    @Test
    fun `GOV-09 요청 뒤 대상이 정지되면 수락은 410이고 owner는 바뀌지 않음`() {
        val owner = owner()
        val target = employees.create()
        val transferToken = requestTransfer(owner, target)
        employees.inTransaction {
            PrincipalTable.update({ PrincipalTable.id eq target.id }) { it[status] = AccountStatus.SUSPENDED.name }
        }

        val response = accept(tokens.issue(target.key), transferToken)

        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(owner.id))
        assertEquals(emptySet(), rolesOf(target.id))
        assertNull(verificationRows(target.id).single()[VerificationTable.consumedAt])
    }

    @Test
    fun `GOV-09 요청 뒤 수동 복구로 owner가 바뀌었으면 수락은 410이고 복구한 owner는 그대로`() {
        val owner = owner()
        val target = employees.create()
        val recovered = employees.create()
        val transferToken = requestTransfer(owner, target)
        // GOV-12 수동 복구와 같은 결과: 기존 owner의 role을 지우고 다른 직원에게 부여
        employees.inTransaction { PrincipalRoleTable.deleteWhere { PrincipalRoleTable.principalId eq owner.id } }
        employees.makeOwner(recovered)

        val response = accept(tokens.issue(target.key), transferToken)

        assertEquals(410, response.status)
        assertEquals("VERIFICATION_EXPIRED", json(response)["code"])
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(recovered.id))
        assertEquals(emptySet(), rolesOf(target.id))
    }

    @Test
    fun `api-conventions 2 수락은 aud를 보지 않지만 system token은 403, partner realm 토큰은 401`() {
        val owner = owner()
        val target = employees.create()
        val transferToken = requestTransfer(owner, target)

        val system = accept(tokens.issue(PrincipalKey(PrincipalType.SYSTEM, UUID.randomUUID())), transferToken)
        val partner = accept(tokens.issue(PrincipalKey(PrincipalType.PARTNER, UUID.randomUUID())), transferToken)

        assertEquals(403, system.status)
        assertEquals("FORBIDDEN", json(system)["code"])
        assertEquals(401, partner.status)
        assertEquals("UNAUTHENTICATED", json(partner)["code"])
        assertNull(verificationRows(target.id).single()[VerificationTable.consumedAt])
    }

    // 취소

    @Test
    fun `GOV-09 취소하면 204이고 발송된 수락 링크는 410, 감사 로그를 남기고 다시 요청할 수 있음`() {
        val owner = owner()
        val target = employees.create()
        val transferToken = requestTransfer(owner, target)

        val response = cancel(ownerToken(owner))

        assertEquals(204, response.status)
        assertNotNull(verificationRows(target.id).single()[VerificationTable.invalidatedAt])
        assertEquals(
            listOf(AuditRow("OWNER_TRANSFER_REQUESTED", owner.id, null), AuditRow("OWNER_TRANSFER_CANCELLED", owner.id, null)),
            audits(target.id),
        )
        val accepted = accept(tokens.issue(target.key), transferToken)
        assertEquals(410, accepted.status)
        assertEquals("VERIFICATION_EXPIRED", json(accepted)["code"])
        assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(owner.id))
        assertEquals(202, request(ownerToken(owner), target.id).status)
    }

    @Test
    fun `진행 중인 양도가 없으면 취소는 404 NOT_FOUND`() {
        val response = cancel(ownerToken(owner()))

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND", json(response)["code"])
    }

    @Test
    fun `GOV-09 수락과 취소가 동시에 오면 먼저 끝난 하나만 성공`() {
        val owner = owner()
        val target = employees.create()
        val transferToken = requestTransfer(owner, target)
        val ownerToken = ownerToken(owner)
        val targetToken = tokens.issue(target.key)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)

        val holder =
            CompletableFuture.runAsync {
                employees.inTransaction {
                    lockAccount.lockAccountById(owner.id)
                    locked.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        locked.await(WAIT_SECONDS, TimeUnit.SECONDS)
        val accepting = CompletableFuture.supplyAsync { accept(targetToken, transferToken) }
        val cancelling = CompletableFuture.supplyAsync { cancel(ownerToken) }
        awaitLockWaiters(2)
        release.countDown()
        holder.get(WAIT_SECONDS, TimeUnit.SECONDS)

        val accepted = accepting.get(WAIT_SECONDS, TimeUnit.SECONDS).status
        val cancelled = cancelling.get(WAIT_SECONDS, TimeUnit.SECONDS).status
        if (accepted == 204) {
            // 수락이 먼저 끝나면 요청한 직원은 이미 owner가 아니므로 취소는 FORBIDDEN (admin.md owner 양도 취소의 에러 순서)
            assertEquals(403, cancelled)
            assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(target.id))
        } else {
            assertEquals(410, accepted)
            assertEquals(204, cancelled)
            assertEquals(setOf(SystemRoles.OWNER.value), rolesOf(owner.id))
        }
    }

    // 요청 형식, 민감정보

    @Test
    fun `필수 필드가 없으면 400 VALIDATION_FAILED`() {
        val owner = owner()

        val requested = post(TRANSFER_PATH, ownerToken(owner), emptyMap())
        val accepted = post(ACCEPT_PATH, tokens.issue(employees.create().key), emptyMap())

        assertEquals(400, requested.status)
        assertEquals("VALIDATION_FAILED", json(requested)["code"])
        assertEquals(400, accepted.status)
        assertEquals("VALIDATION_FAILED", json(accepted)["code"])
    }

    @Test
    fun `SEC-03 수락 링크의 토큰은 응답과 로그에 남지 않음`(output: CapturedOutput) {
        val owner = owner()
        val target = employees.create()
        val requested = request(ownerToken(owner), target.id)
        val transferToken =
            mails.sent
                .filterIsInstance<OwnerTransferRequestMail>()
                .single()
                .token.value
        val forbidden = accept(tokens.issue(employees.create().key), transferToken)
        val accepted = accept(tokens.issue(target.key), transferToken)
        val reused = accept(tokens.issue(target.key), transferToken)

        listOf(requested, forbidden, accepted, reused).forEach { assertFalse(transferToken in it.contentAsString) }
        assertFalse(transferToken in output.all)
        assertFalse(SecretHash.of(transferToken).hex in output.all)
        assertFalse(transferToken in mails.sent.joinToString())
    }

    private fun owner(): CreatedEmployee = employees.create().also(employees::makeOwner)

    private fun ownerToken(employee: CreatedEmployee): String = tokens.issue(employee.key, roles = listOf("auth:owner"))

    /** 양도를 요청하고 수락 메일의 토큰 원문을 돌려줍니다. */
    private fun requestTransfer(
        owner: CreatedEmployee,
        target: CreatedEmployee,
    ): String {
        mails.sent.clear()
        check(request(ownerToken(owner), target.id).status == 202)
        return mails.sent
            .filterIsInstance<OwnerTransferRequestMail>()
            .single()
            .token.value
            .also { mails.sent.clear() }
    }

    /** [owner]가 [target]에게 [issuedAt]에 요청한 것과 같은 `OWNER_TRANSFER`를 저장하고 토큰 원문을 돌려줍니다. */
    private fun issueTransfer(
        owner: CreatedEmployee,
        target: CreatedEmployee,
        issuedAt: Instant,
    ): String =
        employees.inTransaction {
            val issued =
                NewVerification.issue(
                    principalId = target.id,
                    purpose = VerificationPurpose.OWNER_TRANSFER,
                    target = Email(target.email),
                    now = issuedAt,
                    payload = mapOf("requestedBy" to owner.id.toString()),
                )
            issueVerification.issue(issued.verification)
            issued.token.value
        }

    /** 대상이 직원이 아닌 principal. 직원 fixture처럼 [TestEmployees.cleanUp]에서 지웁니다. */
    private fun systemPrincipal(): UUID =
        employees
            .inTransaction {
                PrincipalTable
                    .insertReturning(listOf(PrincipalTable.id)) {
                        it[type] = PrincipalType.SYSTEM.name
                        it[status] = AccountStatus.ACTIVE.name
                    }.single()[PrincipalTable.id]
            }.also(employees::track)

    private fun request(
        token: String?,
        targetId: UUID,
    ): MockHttpServletResponse = post(TRANSFER_PATH, token, mapOf("targetPrincipalId" to targetId.toString()))

    private fun accept(
        token: String?,
        transferToken: String,
    ): MockHttpServletResponse = post(ACCEPT_PATH, token, mapOf("token" to transferToken))

    private fun cancel(token: String?): MockHttpServletResponse =
        mockMvc
            .delete(TRANSFER_PATH) { token?.let { header("Authorization", "Bearer $it") } }
            .andReturn()
            .response

    private fun auditLogs(token: String): MockHttpServletResponse =
        mockMvc
            .get("/admin/audit-logs") { header("Authorization", "Bearer $token") }
            .andReturn()
            .response

    private fun post(
        path: String,
        token: String?,
        body: Map<String, String>,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(body)
            }.andReturn()
            .response

    private fun login(email: String): MockHttpServletResponse =
        post(
            "/realms/internal/login",
            null,
            mapOf(
                "email" to email,
                "password" to PASSWORD,
            ),
        )

    private fun accessTokenOf(response: MockHttpServletResponse): String {
        check(response.status == 200) { "로그인 실패: ${response.status}" }
        return json(response)["accessToken"] as String
    }

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

    private fun rolesOf(principalId: UUID): Set<String> =
        employees.inTransaction { loadPrincipalRoles.findRoleCodes(principalId).map { it.value }.toSet() }

    private fun ownerGrant() =
        employees.inTransaction {
            val ownerRole = checkNotNull(loadRole.findRoleByCode(SystemRoles.OWNER))
            PrincipalRoleTable.selectAll().where { PrincipalRoleTable.roleId eq ownerRole.id }.single()
        }

    private fun verificationRows(principalId: UUID) =
        employees.inTransaction {
            VerificationTable
                .selectAll()
                .where { VerificationTable.principalId eq principalId }
                .orderBy(VerificationTable.id)
                .toList()
        }

    /** 지금 살아 있는 `OWNER_TRANSFER` (GOV-09 "진행 중인 양도"). */
    private fun liveTransfers() =
        employees.inTransaction {
            VerificationTable
                .selectAll()
                .where {
                    (VerificationTable.purpose eq "OWNER_TRANSFER") and
                        VerificationTable.consumedAt.isNull() and
                        VerificationTable.invalidatedAt.isNull()
                }.toList()
                .filter { it[VerificationTable.expiresAt].isAfter(clock.instant()) }
        }

    private fun revokeReasons(principalId: UUID): List<String?> =
        employees.inTransaction {
            RefreshSessionTable
                .selectAll()
                .where { RefreshSessionTable.principalId eq principalId }
                .orderBy(RefreshSessionTable.createdAt)
                .map { it[RefreshSessionTable.revokeReason] }
        }

    /** [principalId]가 대상인 owner 양도 감사 로그 (기록 순서). 로그인 기록(`LOGIN_SUCCEEDED`)은 뺍니다. */
    private fun audits(principalId: UUID): List<AuditRow> =
        employees.inTransaction {
            AuditLogTable
                .selectAll()
                .where { (AuditLogTable.targetType eq "PRINCIPAL") and (AuditLogTable.targetId eq principalId.toString()) }
                .orderBy(AuditLogTable.id)
                .map { AuditRow(it[AuditLogTable.action], it[AuditLogTable.actorId], it[AuditLogTable.detail]) }
                .filter { it.action.startsWith("OWNER_TRANSFER") }
        }

    /** [count]개 이상의 트랜잭션이 행 잠금을 기다릴 때까지 기다립니다. */
    private fun awaitLockWaiters(count: Int) {
        repeat((WAIT_SECONDS * 1000 / POLL_MILLIS).toInt()) {
            val waiting =
                employees.inTransaction {
                    TransactionManager.current().exec("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'") {
                        it.next()
                        it.getInt(1)
                    } ?: 0
                }
            if (waiting >= count) return
            Thread.sleep(POLL_MILLIS)
        }
        error("잠금을 기다리는 트랜잭션이 $count 개보다 적음")
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(response: MockHttpServletResponse): Map<String, Any?> =
        jsonMapper.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    private data class AuditRow(
        val action: String,
        val actorId: UUID?,
        val detail: Map<String, Any?>?,
    )

    private companion object {
        const val TRANSFER_PATH = "/admin/owner/transfer"
        const val ACCEPT_PATH = "/admin/owner/transfer/accept"
        const val ALLOWED_ORIGIN = "https://admin.dozycoffee.test"
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 50L
    }
}
