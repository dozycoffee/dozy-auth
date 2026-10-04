package com.dozycoffee.auth.server.adapter.inbound.startup

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.mail.AfterCommitMailSender
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.EmployeeProfileTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.VerificationTable
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerCommand
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerOutcome
import com.dozycoffee.auth.server.application.port.inbound.BootstrapOwnerUseCase
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadOwnerPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.verification.LoadVerificationPort
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.verification.Verification
import com.dozycoffee.auth.server.domain.verification.VerificationPurpose
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.TestEmployees
import com.dozycoffee.auth.server.support.TestEmployees.CreatedEmployee
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * owner 부트스트랩 (GOV-10, GOV-11, ADR-0010). 기동 리스너는 test 프로필에서 꺼져 있으므로 UseCase를 직접 부르고, 실제 DB에 커밋하며 확인합니다.
 *
 * 메일은 커밋 후 발송(architecture.md §9.3)을 그대로 거쳐 [RecordingMailSender]에 기록합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, TestEmployees::class, OwnerBootstrapIntegrationTest.RecordingMailConfig::class)
@ActiveProfiles("test")
class OwnerBootstrapIntegrationTest {
    @Autowired
    lateinit var bootstrapOwner: BootstrapOwnerUseCase

    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var mails: RecordingMailSender

    @Autowired
    lateinit var loadOwner: LoadOwnerPort

    @Autowired
    lateinit var loadEmployee: LoadEmployeePort

    @Autowired
    lateinit var loadRoles: LoadPrincipalRolesPort

    @Autowired
    lateinit var loadVerification: LoadVerificationPort

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val jsonMapper = JsonMapper.builder().build()

    @BeforeEach
    fun setUp() {
        mails.sent.clear()
        assertNull(ownerId(), "다른 테스트가 owner를 남겼습니다")
    }

    @AfterEach
    fun cleanUp() {
        ownerId()?.let(employees::track)
        employees.cleanUp()
    }

    @Test
    fun `GOV-11 owner가 없으면 설정 이메일로 PENDING 직원을 만들고 auth owner를 부여하고 초대함`() {
        val email = newEmail()

        val result = bootstrap(email)

        assertEquals(BootstrapOwnerOutcome.OWNER_INVITED, result.outcome)
        val ownerId = assertNotNull(ownerId())
        val owner = assertNotNull(employees.inTransaction { loadEmployee.findEmployeeById(ownerId) })
        assertEquals(email, owner.profile.email.value)
        assertEquals(AccountStatus.PENDING, owner.account.status)
        assertEquals(listOf("auth:owner"), employees.inTransaction { loadRoles.findRoleCodes(ownerId) }.map { it.value })
        assertNull(grantedBy(ownerId))
        val invitation = assertNotNull(liveInvitation(ownerId))
        assertEquals(email, invitation.target.value)
        assertEquals(invitation.createdAt.plus(AuthPolicy.INVITATION_TTL), invitation.expiresAt)
    }

    @Test
    fun `GOV-11 owner 초대 메일은 저장한 초대의 토큰과 만료 시각을 담아 owner 이메일로 보냄`() {
        val email = newEmail()

        bootstrap(email)

        val mail = mails.sent.single() as EmployeeInvitationMail
        val invitation = assertNotNull(liveInvitation(assertNotNull(ownerId())))
        assertEquals(email, mail.to.value)
        assertEquals(invitation.tokenHash, SecretHash.of(mail.token.value))
        assertEquals(invitation.expiresAt, mail.expiresAt)
    }

    @Test
    fun `GOV-11 부트스트랩은 행위자 없는 EMPLOYEE_INVITED와 ROLE_GRANTED를 남김`() {
        bootstrap(newEmail())

        val ownerId = assertNotNull(ownerId())
        val audits =
            employees.inTransaction {
                AuditLogTable
                    .selectAll()
                    .where { AuditLogTable.targetId eq ownerId.toString() }
                    .orderBy(AuditLogTable.id)
                    .toList()
            }
        assertEquals(listOf("EMPLOYEE_INVITED", "ROLE_GRANTED"), audits.map { it[AuditLogTable.action] })
        audits.forEach {
            assertNull(it[AuditLogTable.actorId])
            assertNull(it[AuditLogTable.actorType])
            assertEquals("PRINCIPAL", it[AuditLogTable.targetType])
        }
        assertEquals(mapOf("roles" to listOf("auth:owner")), audits[1][AuditLogTable.detail])
    }

    @Test
    fun `GOV-11 owner가 PENDING이고 초대가 만료됐으면 owner 이메일로 다시 발급하고 설정 이메일은 무시함`() {
        val owner = pendingOwner()
        val expired = employees.issueInvitation(owner, now().minus(AuthPolicy.INVITATION_TTL).minus(Duration.ofMinutes(1)))
        val employeesBefore = employeeCount()

        val result = bootstrap(newEmail())

        assertEquals(BootstrapOwnerOutcome.INVITATION_REISSUED, result.outcome)
        assertTrue(result.configuredEmailIgnored)
        assertEquals(owner.id, ownerId())
        val invitation = assertNotNull(liveInvitation(owner.id))
        assertTrue(invitation.tokenHash != SecretHash.of(expired))
        val mail = mails.sent.single() as EmployeeInvitationMail
        assertEquals(owner.email, mail.to.value)
        assertEquals(invitation.tokenHash, SecretHash.of(mail.token.value))
        assertEquals(employeesBefore, employeeCount(), "새 직원을 만들지 않음")
        assertEquals(emptyList(), employees.auditActions(owner.id))
    }

    @Test
    fun `GOV-11 owner가 PENDING이고 초대가 살아 있으면 다시 발급하지 않음`() {
        val owner = pendingOwner()
        val token = employees.issueInvitation(owner, now())

        val result = bootstrap(owner.email)

        assertEquals(BootstrapOwnerOutcome.INVITATION_LIVE, result.outcome)
        assertEquals(false, result.configuredEmailIgnored)
        assertEquals(SecretHash.of(token), liveInvitation(owner.id)?.tokenHash)
        assertEquals(1L, invitationCount(owner.id))
        assertEquals(emptyList(), mails.sent)
    }

    @Test
    fun `GOV-11 owner가 ACTIVE이면 설정 이메일을 무시하고 아무것도 바꾸지 않음`() {
        val owner = employees.create(status = AccountStatus.ACTIVE)
        employees.makeOwner(owner)
        val employeesBefore = employeeCount()

        val result = bootstrap(newEmail())

        assertEquals(BootstrapOwnerOutcome.OWNER_ACTIVE, result.outcome)
        assertTrue(result.configuredEmailIgnored)
        assertEquals(owner.id, ownerId())
        assertEquals(employeesBefore, employeeCount())
        assertEquals(0L, invitationCount(owner.id))
        assertEquals(emptyList(), employees.auditActions(owner.id))
        assertEquals(emptyList(), mails.sent)
    }

    @Test
    fun `GOV-11 owner가 없는데 설정 이메일이 없으면 아무것도 만들지 않음`() {
        val employeesBefore = employeeCount()

        val result = bootstrapOwner.bootstrap(BootstrapOwnerCommand(ownerEmail = null))

        assertEquals(BootstrapOwnerOutcome.OWNER_EMAIL_MISSING, result.outcome)
        assertNull(ownerId())
        assertEquals(employeesBefore, employeeCount())
        assertEquals(emptyList(), mails.sent)
    }

    @Test
    fun `GOV-11 설정 이메일을 다른 직원이 쓰고 있으면 그 직원을 owner로 만들지 않음`() {
        val existing = employees.create(status = AccountStatus.ACTIVE)

        val result = bootstrap(existing.email.uppercase())

        assertEquals(BootstrapOwnerOutcome.OWNER_EMAIL_IN_USE, result.outcome)
        assertNull(ownerId())
        assertEquals(emptyList(), employees.inTransaction { loadRoles.findRoleCodes(existing.id) })
        assertEquals(1L, employeeCount(existing.email))
        assertEquals(emptyList(), mails.sent)
    }

    @Test
    fun `GOV-11 두 인스턴스가 동시에 부트스트랩해도 owner와 초대는 하나만 생김`() {
        val email = newEmail()
        val instances = 2
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(instances)

        val outcomes =
            try {
                val futures = List(instances) { executor.submit<BootstrapOwnerOutcome> { start.await().let { bootstrap(email).outcome } } }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        assertEquals(
            setOf(BootstrapOwnerOutcome.OWNER_INVITED, BootstrapOwnerOutcome.INVITATION_LIVE),
            outcomes.toSet(),
            outcomes.toString(),
        )
        val ownerId = assertNotNull(ownerId())
        assertEquals(1L, employeeCount(email))
        assertEquals(1L, invitationCount(ownerId))
        assertEquals(1, mails.sent.size)
        assertEquals(listOf("EMPLOYEE_INVITED", "ROLE_GRANTED"), employees.auditActions(ownerId))
    }

    @Test
    fun `부트스트랩한 owner는 메일의 초대를 수락한 뒤 로그인할 수 있음`() {
        val email = newEmail()
        bootstrap(email)
        val token = (mails.sent.single() as EmployeeInvitationMail).token.value

        val accepted = post("/realms/internal/invitations/accept", mapOf("token" to token, "password" to PASSWORD))
        val login = post("/realms/internal/login", mapOf("email" to email, "password" to PASSWORD))

        assertEquals(204, accepted.status)
        assertEquals(200, login.status)
        assertEquals(AccountStatus.ACTIVE, employees.account(assertNotNull(ownerId())).status)
    }

    private fun bootstrap(email: String) = bootstrapOwner.bootstrap(BootstrapOwnerCommand(Email(email)))

    private fun pendingOwner(): CreatedEmployee =
        employees.create(status = AccountStatus.PENDING, password = null).also(employees::makeOwner)

    private fun ownerId(): UUID? = query { loadOwner.findOwnerId() }

    private fun liveInvitation(principalId: UUID): Verification? =
        query { loadVerification.findLive(principalId, VerificationPurpose.EMPLOYEE_INVITATION, clock.instant()) }

    private fun grantedBy(principalId: UUID): UUID? =
        query {
            PrincipalRoleTable.selectAll().where { PrincipalRoleTable.principalId eq principalId }.single()[PrincipalRoleTable.grantedBy]
        }

    /** DB에 있는 직원 수. 다른 테스트의 데이터가 남아 있어도 되도록 전후 차이로 비교합니다. */
    private fun employeeCount(): Long = employees.inTransaction { EmployeeProfileTable.selectAll().count() }

    /** 대소문자를 무시하고 [email]을 쓰는 직원 수. */
    private fun employeeCount(email: String): Long =
        employees.inTransaction {
            EmployeeProfileTable.selectAll().where { EmployeeProfileTable.email.lowerCase() eq email.lowercase() }.count()
        }

    /** 결과가 `null`일 수 있는 조회를 트랜잭션 안에서 합니다. */
    private fun <T> query(block: () -> T?): T? = TransactionTemplate(transactionManager).execute { block() }

    private fun invitationCount(principalId: UUID): Long =
        employees.inTransaction { VerificationTable.selectAll().where { VerificationTable.principalId eq principalId }.count() }

    private fun post(
        path: String,
        body: Map<String, String>,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = jsonMapper.writeValueAsString(body)
            }.andReturn()
            .response

    /** DB가 시각을 마이크로초까지 저장하므로 초 단위로 맞춘 현재 시각. */
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun newEmail(): String = "owner-${UUID.randomUUID()}@dozycoffee.test"

    /** 커밋 후 발송을 그대로 거치되, 보내는 대신 기록합니다. 발송 스레드 대신 커밋한 스레드에서 바로 기록합니다. */
    @TestConfiguration(proxyBeanMethods = false)
    class RecordingMailConfig {
        @Bean
        fun recordingMailSender(): RecordingMailSender = RecordingMailSender()

        @Bean
        @Primary
        fun recordingSendMailPort(recorder: RecordingMailSender): SendMailPort = AfterCommitMailSender(recorder) { it.run() }
    }

    private companion object {
        const val PASSWORD = "owner-horse-battery-staple"
    }
}
