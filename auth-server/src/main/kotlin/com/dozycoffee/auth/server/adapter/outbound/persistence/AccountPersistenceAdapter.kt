package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.application.port.outbound.account.ChangeAccountStatusPort
import com.dozycoffee.auth.server.application.port.outbound.account.CreateEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LoadAccountPort
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.account.LockAccountPort
import com.dozycoffee.auth.server.application.port.outbound.account.RecordLoginFailurePort
import com.dozycoffee.auth.server.application.port.outbound.account.ResetLoginFailuresPort
import com.dozycoffee.auth.server.application.port.outbound.account.ScrubEmployeeProfilePort
import com.dozycoffee.auth.server.application.port.outbound.account.UpdateEmployeeProfilePort
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.account.Account
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.DuplicateEmailException
import com.dozycoffee.auth.server.domain.account.Employee
import com.dozycoffee.auth.server.domain.account.EmployeeProfile
import com.dozycoffee.auth.server.domain.account.LoginFailureResult
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * principal과 직원 profile을 저장합니다 (docs/data-model.md §3.1, §3.2).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 *
 * - 이메일 유일성(`lower(email)` 표현식 UNIQUE 인덱스) 위반은 `INSERT ... ON CONFLICT DO NOTHING`으로 받습니다. 예외로 받으면
 *   PostgreSQL이 트랜잭션 전체를 중단 상태로 만들기 때문입니다. 충돌 대상을 적지 않으면 표현식 인덱스도 대상이 됩니다.
 *   `employee_profile`에 남은 유일성 제약은 이메일뿐이고 기본 키는 방금 만든 principal이라, 넣은 행이 없으면 이메일 중복입니다.
 * - 상태 변경은 현재 상태가 기대한 값일 때만 바꾸는 `UPDATE ... WHERE status = ?` 한 문장입니다.
 * - 로그인 실패 기록은 `SELECT ... FOR UPDATE`로 행을 잠그고 `Account.recordLoginFailure`로 판단한 뒤 저장합니다. 동시에 실패한
 *   요청은 앞 요청이 커밋할 때까지 기다렸다가 커밋된 값을 읽으므로 횟수를 잃지 않고, 잠금 규칙은 도메인 한 곳에만 둡니다.
 * - `updated_at`은 DB의 `now()`가 아니라 호출한 쪽이 넘긴 `Clock` 시각입니다.
 */
@Component
class AccountPersistenceAdapter :
    LoadAccountPort,
    LockAccountPort,
    LoadEmployeePort,
    CreateEmployeePort,
    ChangeAccountStatusPort,
    UpdateEmployeeProfilePort,
    RecordLoginFailurePort,
    ResetLoginFailuresPort,
    ScrubEmployeeProfilePort {
    override fun findAccountById(id: UUID): Account? =
        PrincipalTable
            .selectAll()
            .where { PrincipalTable.id eq id }
            .singleOrNull()
            ?.toAccount()

    // FOR NO KEY UPDATE: 같은 계정의 관리 작업·상태 변경과는 차례로 처리하지만, 다른 테이블이 이 계정을 참조하는 외래 키 검사
    // (예: 이 계정이 부여한 role의 granted_by)는 막지 않습니다. 서로에게 부여하는 두 요청이 교착되지 않게 하기 위해서입니다.
    override fun lockAccountById(id: UUID): Account? =
        PrincipalTable
            .selectAll()
            .where { PrincipalTable.id eq id }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate())
            .singleOrNull()
            ?.toAccount()

    override fun findEmployeeById(id: UUID): Employee? = findEmployee { EmployeeProfileTable.principalId eq id }

    override fun findEmployeeByEmail(email: Email): Employee? = findEmployee { EmployeeProfileTable.email.lowerCase() eq email.lookupKey }

    override fun createEmployee(
        email: Email,
        name: String,
        phone: String?,
        address: String?,
        createdAt: Instant,
    ): Employee {
        val id =
            PrincipalTable
                .insertReturning(listOf(PrincipalTable.id)) {
                    it[PrincipalTable.type] = PrincipalType.EMPLOYEE.name
                    it[PrincipalTable.status] = AccountStatus.PENDING.name
                    it[PrincipalTable.createdAt] = createdAt
                    it[PrincipalTable.updatedAt] = createdAt
                }.single()[PrincipalTable.id]
        val profile = EmployeeProfile(id, email, name, phone, address)
        val inserted =
            EmployeeProfileTable
                .insertReturning(listOf(EmployeeProfileTable.principalId), ignoreErrors = true) {
                    it[EmployeeProfileTable.principalId] = id
                    it[EmployeeProfileTable.email] = email.value
                    it[EmployeeProfileTable.name] = name
                    it[EmployeeProfileTable.phone] = phone
                    it[EmployeeProfileTable.address] = address
                    it[EmployeeProfileTable.createdAt] = createdAt
                    it[EmployeeProfileTable.updatedAt] = createdAt
                }.any()
        if (!inserted) {
            // 방금 만든 principal은 아직 아무도 참조하지 않으므로 지워서 profile 없는 principal을 남기지 않는다
            PrincipalTable.deleteWhere { PrincipalTable.id eq id }
            throw DuplicateEmailException()
        }
        val account = Account(id, PrincipalType.EMPLOYEE, AccountStatus.PENDING, 0, null, null)
        return Employee(account, profile)
    }

    override fun changeStatus(
        id: UUID,
        from: AccountStatus,
        to: AccountStatus,
        changedAt: Instant,
    ): Boolean =
        PrincipalTable.update({ (PrincipalTable.id eq id) and (PrincipalTable.status eq from.name) }) {
            it[PrincipalTable.status] = to.name
            it[PrincipalTable.updatedAt] = changedAt
            if (to == AccountStatus.DEACTIVATED) it[PrincipalTable.deactivatedAt] = changedAt
        } > 0

    override fun updateEmployeeProfile(
        principalId: UUID,
        name: String,
        phone: String?,
        address: String?,
        updatedAt: Instant,
    ): Boolean =
        EmployeeProfileTable.update({ EmployeeProfileTable.principalId eq principalId }) {
            it[EmployeeProfileTable.name] = name
            it[EmployeeProfileTable.phone] = phone
            it[EmployeeProfileTable.address] = address
            it[EmployeeProfileTable.updatedAt] = updatedAt
        } > 0

    override fun recordLoginFailure(
        id: UUID,
        now: Instant,
    ): LoginFailureResult? {
        val account =
            PrincipalTable
                .selectAll()
                .where { PrincipalTable.id eq id }
                .forUpdate()
                .singleOrNull()
                ?.toAccount()
                ?: return null
        val result = account.recordLoginFailure(now)
        PrincipalTable.update({ PrincipalTable.id eq id }) {
            it[PrincipalTable.failedLoginCount] = result.account.failedLoginCount
            it[PrincipalTable.lockedUntil] = result.account.lockedUntil
            it[PrincipalTable.updatedAt] = now
        }
        return result
    }

    override fun resetLoginFailures(
        id: UUID,
        updatedAt: Instant,
    ): Boolean =
        PrincipalTable.update({ PrincipalTable.id eq id }) {
            it[PrincipalTable.failedLoginCount] = 0
            it[PrincipalTable.lockedUntil] = null
            it[PrincipalTable.updatedAt] = updatedAt
        } > 0

    override fun scrubEmployeeProfile(
        principalId: UUID,
        scrubbedAt: Instant,
    ): Boolean {
        val scrubbed =
            EmployeeProfileTable
                .selectAll()
                .where { EmployeeProfileTable.principalId eq principalId }
                .singleOrNull()
                ?.toEmployeeProfile()
                ?.scrubbed()
                ?: return false
        return EmployeeProfileTable.update({ EmployeeProfileTable.principalId eq principalId }) {
            it[EmployeeProfileTable.email] = scrubbed.email.value
            it[EmployeeProfileTable.name] = scrubbed.name
            it[EmployeeProfileTable.phone] = scrubbed.phone
            it[EmployeeProfileTable.address] = scrubbed.address
            it[EmployeeProfileTable.updatedAt] = scrubbedAt
        } > 0
    }

    private fun findEmployee(condition: () -> Op<Boolean>): Employee? =
        EmployeeProfileTable
            .join(PrincipalTable, JoinType.INNER, EmployeeProfileTable.principalId, PrincipalTable.id)
            .selectAll()
            .where(condition)
            .singleOrNull()
            ?.let { Employee(it.toAccount(), it.toEmployeeProfile()) }

    private fun ResultRow.toAccount() =
        Account(
            id = this[PrincipalTable.id],
            type = PrincipalType.valueOf(this[PrincipalTable.type]),
            status = AccountStatus.valueOf(this[PrincipalTable.status]),
            failedLoginCount = this[PrincipalTable.failedLoginCount],
            lockedUntil = this[PrincipalTable.lockedUntil],
            deactivatedAt = this[PrincipalTable.deactivatedAt],
        )

    private fun ResultRow.toEmployeeProfile() =
        EmployeeProfile(
            principalId = this[EmployeeProfileTable.principalId],
            email = Email(this[EmployeeProfileTable.email]),
            name = this[EmployeeProfileTable.name],
            phone = this[EmployeeProfileTable.phone],
            address = this[EmployeeProfileTable.address],
        )
}
