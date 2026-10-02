package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.core.PrincipalKey
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.adapter.outbound.persistence.AuditLogTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.PasswordCredentialTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalTable
import com.dozycoffee.auth.server.application.port.outbound.account.ChangeAccountStatusPort
import com.dozycoffee.auth.server.application.port.outbound.account.CreateEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CreateRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadAudiencePort
import com.dozycoffee.auth.server.application.port.outbound.crypto.HashPasswordPort
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.domain.credential.RawPassword
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestComponent
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * API 테스트용 직원 계정을 실제 DB에 만듭니다. 로그인 API는 자기 트랜잭션으로 커밋하므로 테스트도 커밋된 데이터를 씁니다.
 *
 * 테스트끼리 겹치지 않도록 이메일과 role code는 만들 때마다 새로 정합니다. 기대값은 각 테스트에 씁니다.
 */
@TestComponent
class TestEmployees(
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val createEmployee: CreateEmployeePort,
    @Autowired private val changeStatus: ChangeAccountStatusPort,
    @Autowired private val loadAccount: LoadAccountPort,
    @Autowired private val hashPassword: HashPasswordPort,
    @Autowired private val loadAudience: LoadAudiencePort,
    @Autowired private val createRole: CreateRolePort,
    @Autowired private val grantRole: GrantRolePort,
) {
    /**
     * 직원을 만듭니다.
     *
     * @param password `null`이면 비밀번호를 만들지 않습니다 (초대 수락 전 직원)
     * @param roles 부여할 role. 없는 role이면 새로 정의합니다
     */
    fun create(
        status: AccountStatus = AccountStatus.ACTIVE,
        password: String? = PASSWORD,
        roles: List<RoleCode> = emptyList(),
        name: String = "김도윤",
    ): CreatedEmployee =
        inTransaction {
            val email = Email("employee-${UUID.randomUUID()}@dozycoffee.test")
            val employee = createEmployee.createEmployee(email, name, null, null, NOW)
            val id = employee.account.id
            if (password != null) {
                PasswordCredentialTable.insert {
                    it[principalId] = id
                    it[passwordHash] = hashPassword.hash(RawPassword(password)).encoded
                }
            }
            // 상태는 전이 규칙을 거치지 않고 바로 맞춥니다 (상태별 로그인 응답만 확인)
            if (status != AccountStatus.PENDING) {
                PrincipalTable.update({ PrincipalTable.id eq id }) { it[PrincipalTable.status] = status.name }
            }
            roles.forEach { code -> grantRole.grant(RoleGrant(id, roleId(code), null, NOW)) }
            CreatedEmployee(PrincipalKey(PrincipalType.EMPLOYEE, id), email.value, name)
        }

    /** 새 role code. 테스트마다 다른 code를 써서 role 정의가 겹치지 않게 합니다. */
    fun newRoleCode(audience: String): RoleCode = RoleCode(audience, "role_${UUID.randomUUID().toString().replace("-", "")}")

    fun account(id: UUID): Account = inTransaction { checkNotNull(loadAccount.findAccountById(id)) }

    fun lockedUntil(id: UUID): Instant? =
        inTransaction { PrincipalTable.selectAll().where { PrincipalTable.id eq id }.single()[PrincipalTable.lockedUntil] }

    /** [principalId]가 대상인 감사 로그 action (기록 순서). */
    fun auditActions(principalId: UUID): List<String> =
        inTransaction {
            AuditLogTable
                .selectAll()
                .where { AuditLogTable.targetId eq principalId.toString() }
                .orderBy(AuditLogTable.id)
                .map { it[AuditLogTable.action] }
        }

    /** [principalId]가 대상인 감사 로그의 detail (기록 순서). */
    fun auditDetails(principalId: UUID): List<Map<String, Any?>?> =
        inTransaction {
            AuditLogTable
                .selectAll()
                .where { AuditLogTable.targetId eq principalId.toString() }
                .orderBy(AuditLogTable.id)
                .map { it[AuditLogTable.detail] }
        }

    fun <T> inTransaction(block: () -> T): T = checkNotNull(TransactionTemplate(transactionManager).execute { block() })

    private fun roleId(code: RoleCode): Long {
        val audience = checkNotNull(loadAudience.findAudienceByCode(code.audience))
        return createRole.createRole(audience, code.code, code.code, null, null, NOW).id
    }

    data class CreatedEmployee(
        val key: PrincipalKey,
        val email: String,
        val name: String,
    ) {
        val id: UUID get() = key.id
    }

    companion object {
        const val PASSWORD = "correct-horse-battery"
        const val WRONG_PASSWORD = "wrong-horse-battery"
        private val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
