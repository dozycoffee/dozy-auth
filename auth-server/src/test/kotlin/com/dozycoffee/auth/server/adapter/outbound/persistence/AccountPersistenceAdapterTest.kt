package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.EmployeeProfileTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalTable
import com.dozycoffee.auth.server.application.port.outbound.account.EmployeeSearchCriteria
import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.PageRequest
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.DuplicateEmailException
import com.dozycoffee.auth.server.domain.account.Employee
import com.dozycoffee.auth.server.domain.account.EmployeeProfile
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** principal과 직원 profile의 저장 (docs/data-model.md §3.1, §3.2). 잠금 기준은 `AuthPolicy`, 파기 값은 ACC-04의 문자열 그대로입니다. */
@PersistenceAdapterTest
class AccountPersistenceAdapterTest {
    private val adapter = AccountPersistenceAdapter()

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `직원을 만들면 PENDING 상태로 DB가 만든 id와 함께 저장함`() {
        val created = adapter.createEmployee(Email("Kim.Barista@DozyCoffee.com"), "김바리", "010-1234-5678", "서울시 성동구", NOW)

        val id = created.account.id
        assertEquals(7, id.version())
        assertEquals(PrincipalType.EMPLOYEE, created.account.type)
        assertEquals(AccountStatus.PENDING, created.account.status)
        assertEquals(0, created.account.failedLoginCount)
        assertNull(created.account.lockedUntil)
        assertNull(created.account.deactivatedAt)
        assertEquals(EmployeeProfile(id, Email("Kim.Barista@DozyCoffee.com"), "김바리", "010-1234-5678", "서울시 성동구"), created.profile)
        assertEquals(created, adapter.findEmployeeById(id))
        assertEquals(created.account, adapter.findAccountById(id))
    }

    @Test
    fun `직원을 만들면 생성 시각과 수정 시각을 넘긴 시각으로 남김`() {
        val id = createEmployee().account.id

        val principal = principalRow(id)
        val profile = EmployeeProfileTable.selectAll().where { EmployeeProfileTable.principalId eq id }.single()
        assertEquals(NOW, principal[PrincipalTable.createdAt])
        assertEquals(NOW, principal[PrincipalTable.updatedAt])
        assertEquals(NOW, profile[EmployeeProfileTable.createdAt])
        assertEquals(NOW, profile[EmployeeProfileTable.updatedAt])
    }

    @Test
    fun `이메일로 직원을 찾을 때 대소문자를 구분하지 않고 입력값 그대로 돌려줌`() {
        val created = createEmployee(email = "Kim.Barista@DozyCoffee.com")

        val found = adapter.findEmployeeByEmail(Email("kim.barista@dozycoffee.COM"))

        assertEquals(created, found)
        assertEquals("Kim.Barista@DozyCoffee.com", found?.profile?.email?.value)
    }

    @Test
    fun `없는 계정과 직원은 null`() {
        val unknown = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f")

        assertNull(adapter.findAccountById(unknown))
        assertNull(adapter.findEmployeeById(unknown))
        assertNull(adapter.findEmployeeByEmail(Email("nobody@dozycoffee.com")))
    }

    @Test
    fun `여러 계정을 id로 한 번에 조회하고 없는 id는 빠짐`() {
        val first = createEmployee().account
        val second = createEmployee(email = "second@dozycoffee.com").account
        val unknown = UUID.fromString("0199a3c4-0000-7000-8000-000000000000")

        assertEquals(mapOf(first.id to first, second.id to second), adapter.findAccountsByIds(listOf(first.id, second.id, unknown)))
        assertEquals(emptyMap(), adapter.findAccountsByIds(emptyList()))
    }

    @Test
    fun `직원 profile이 없는 principal은 직원으로 조회하지 않음`() {
        val systemId =
            PrincipalTable
                .insertReturning(listOf(PrincipalTable.id)) {
                    it[type] = "SYSTEM"
                    it[status] = "ACTIVE"
                }.single()[PrincipalTable.id]

        assertNull(adapter.findEmployeeById(systemId))
        assertEquals(PrincipalType.SYSTEM, adapter.findAccountById(systemId)?.type)
    }

    @Test
    fun `대소문자만 다른 이메일로 직원을 만들면 DUPLICATE_EMAIL이고 아무것도 남기지 않음`() {
        val existing = createEmployee(email = "Kim.Barista@DozyCoffee.com")
        val principalsBefore = PrincipalTable.selectAll().count()

        val error = assertFailsWith<DuplicateEmailException> { createEmployee(email = "KIM.BARISTA@dozycoffee.com") }

        assertEquals("DUPLICATE_EMAIL", error.code)
        assertEquals(409, error.status)
        assertEquals(principalsBefore, PrincipalTable.selectAll().count())
        assertEquals(existing, adapter.findEmployeeByEmail(Email("kim.barista@dozycoffee.com")))
    }

    @Test
    fun `이메일 중복 뒤에도 같은 트랜잭션에서 계속 쓰고 저장할 수 있음`() {
        createEmployee(email = "kim@dozycoffee.com")
        assertFailsWith<DuplicateEmailException> { createEmployee(email = "Kim@DozyCoffee.com") }

        val next = createEmployee(email = "lee@dozycoffee.com")

        assertEquals(next, adapter.findEmployeeByEmail(Email("lee@dozycoffee.com")))
    }

    @Test
    fun `현재 상태가 기대한 상태이면 상태를 바꾸고 수정 시각을 남김`() {
        val id = createEmployee().account.id

        assertTrue(adapter.changeStatus(id, AccountStatus.PENDING, AccountStatus.ACTIVE, LATER))

        assertEquals(AccountStatus.ACTIVE, adapter.findAccountById(id)?.status)
        assertEquals(LATER, principalRow(id)[PrincipalTable.updatedAt])
        assertNull(adapter.findAccountById(id)?.deactivatedAt)
    }

    @Test
    fun `현재 상태가 기대한 상태가 아니면 바꾸지 않음`() {
        val id = createEmployee().account.id

        assertFalse(adapter.changeStatus(id, AccountStatus.ACTIVE, AccountStatus.SUSPENDED, LATER))

        assertEquals(AccountStatus.PENDING, adapter.findAccountById(id)?.status)
        assertEquals(NOW, principalRow(id)[PrincipalTable.updatedAt])
    }

    @Test
    fun `없는 계정의 상태는 바꾸지 않음`() {
        assertFalse(adapter.changeStatus(UUID.randomUUID(), AccountStatus.PENDING, AccountStatus.ACTIVE, LATER))
    }

    @Test
    fun `DEACTIVATED로 바꾸면 전환 시각을 남김`() {
        val id = createEmployee().account.id

        assertTrue(adapter.changeStatus(id, AccountStatus.PENDING, AccountStatus.DEACTIVATED, LATER))

        val account = adapter.findAccountById(id)
        assertEquals(AccountStatus.DEACTIVATED, account?.status)
        assertEquals(LATER, account?.deactivatedAt)
    }

    @Test
    fun `직원 정보를 수정해도 이메일은 바꾸지 않음`() {
        val id = createEmployee(email = "kim@dozycoffee.com").account.id

        assertTrue(adapter.updateEmployeeProfile(id, "김바리스타", null, "부산시 해운대구", LATER))

        assertEquals(EmployeeProfile(id, Email("kim@dozycoffee.com"), "김바리스타", null, "부산시 해운대구"), adapter.findEmployeeById(id)?.profile)
        assertEquals(
            LATER,
            EmployeeProfileTable
                .selectAll()
                .where {
                    EmployeeProfileTable.principalId eq id
                }.single()[EmployeeProfileTable.updatedAt],
        )
    }

    @Test
    fun `없는 직원의 정보는 수정하지 않음`() {
        assertFalse(adapter.updateEmployeeProfile(UUID.randomUUID(), "김", null, null, LATER))
    }

    @Test
    fun `LGN-01 기준 횟수에 못 미친 실패는 횟수만 늘림`() {
        val id = createEmployee().account.id

        val results = (1 until AuthPolicy.LOGIN_LOCK_THRESHOLD).map { adapter.recordLoginFailure(id, LATER) }

        assertTrue(results.none { it?.locked == true })
        val account = adapter.findAccountById(id)
        assertEquals(AuthPolicy.LOGIN_LOCK_THRESHOLD - 1, account?.failedLoginCount)
        assertNull(account?.lockedUntil)
        assertEquals(LATER, principalRow(id)[PrincipalTable.updatedAt])
    }

    @Test
    fun `LGN-01 기준 횟수에 도달하면 잠금 시간만큼 잠그고 실패 횟수를 되돌림`() {
        val id = createEmployee().account.id
        repeat(AuthPolicy.LOGIN_LOCK_THRESHOLD - 1) { adapter.recordLoginFailure(id, NOW) }

        val result = adapter.recordLoginFailure(id, LATER)

        assertEquals(true, result?.locked)
        val account = adapter.findAccountById(id)
        assertEquals(LATER.plus(AuthPolicy.LOGIN_LOCK_DURATION), account?.lockedUntil)
        assertEquals(0, account?.failedLoginCount)
        assertEquals(account, result?.account)
    }

    @Test
    fun `없는 계정의 로그인 실패는 기록하지 않음`() {
        assertNull(adapter.recordLoginFailure(UUID.randomUUID(), NOW))
    }

    @Test
    fun `LGN-01 로그인 실패 횟수와 잠금을 초기화함`() {
        val id = createEmployee().account.id
        repeat(AuthPolicy.LOGIN_LOCK_THRESHOLD) { adapter.recordLoginFailure(id, NOW) }
        adapter.recordLoginFailure(id, NOW)

        assertTrue(adapter.resetLoginFailures(id, LATER))

        val account = adapter.findAccountById(id)
        assertEquals(0, account?.failedLoginCount)
        assertNull(account?.lockedUntil)
        assertEquals(LATER, principalRow(id)[PrincipalTable.updatedAt])
        assertFalse(adapter.resetLoginFailures(UUID.randomUUID(), LATER))
    }

    @Test
    fun `ACC-04 개인정보를 파기하면 정해진 값으로 바꾸고 원래 이메일로 다시 만들 수 있음`() {
        val id = createEmployee(email = "kim@dozycoffee.com").account.id

        assertTrue(adapter.scrubEmployeeProfile(id, LATER))

        assertEquals(EmployeeProfile(id, Email("deleted+$id@invalid.local"), "탈퇴 사용자", null, null), adapter.findEmployeeById(id)?.profile)
        assertEquals(
            LATER,
            EmployeeProfileTable
                .selectAll()
                .where {
                    EmployeeProfileTable.principalId eq id
                }.single()[EmployeeProfileTable.updatedAt],
        )
        assertNull(adapter.findEmployeeByEmail(Email("kim@dozycoffee.com")))
        createEmployee(email = "kim@dozycoffee.com")
    }

    @Test
    fun `직원 profile이 없으면 파기하지 않음`() {
        assertFalse(adapter.scrubEmployeeProfile(UUID.randomUUID(), LATER))
    }

    /**
     * 커밋된 행에 여러 트랜잭션이 동시에 실패를 기록해도 횟수를 잃지 않고, 기준 횟수에 도달한 한 요청만 잠급니다.
     * 테스트 트랜잭션 밖에서 실행하고 만든 행은 직접 지웁니다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `LGN-01 동시에 실패해도 모든 실패를 세고 한 번만 잠금`() {
        val tx = TransactionTemplate(transactionManager)
        val id = checkNotNull(tx.execute { createEmployee(email = "concurrent-${UUID.randomUUID()}@dozycoffee.com") }).account.id
        val threads = AuthPolicy.LOGIN_LOCK_THRESHOLD
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val futures =
                (1..threads).map {
                    executor.submit<Boolean> {
                        start.await()
                        checkNotNull(tx.execute { adapter.recordLoginFailure(id, NOW) }).locked
                    }
                }
            start.countDown()
            val locks = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, locks.count { it })
            val account = tx.execute { adapter.findAccountById(id) }
            assertNotNull(account)
            assertEquals(0, account.failedLoginCount)
            assertEquals(NOW.plus(AuthPolicy.LOGIN_LOCK_DURATION), account.lockedUntil)
        } finally {
            executor.shutdownNow()
            tx.executeWithoutResult {
                EmployeeProfileTable.deleteWhere { EmployeeProfileTable.principalId eq id }
                PrincipalTable.deleteWhere { PrincipalTable.id eq id }
            }
        }
    }

    @Test
    fun `직원 기록은 생성 시각과 계정·profile 중 늦은 수정 시각을 담고 직원이 아니면 null`() {
        val id = createEmployee().account.id
        val later = NOW.plusSeconds(60)
        adapter.updateEmployeeProfile(id, "김도윤", null, null, later)

        val record = assertNotNull(adapter.findEmployeeRecord(id))
        assertEquals(NOW, record.createdAt)
        assertEquals(later, record.updatedAt)
        assertEquals(record, adapter.lockEmployeeRecord(id))
        assertNull(adapter.findEmployeeRecord(UUID.randomUUID()))
    }

    @Test
    fun `이름 또는 이메일에 검색어가 들어간 직원을 대소문자 무시하고 찾고 퍼센트와 밑줄은 글자 그대로 찾음`() {
        val byName = createEmployee(email = "a@dozycoffee.com", name = "Zq_%바리스타")
        val byEmail = createEmployee(email = "ZQ_%Kim@dozycoffee.com", name = "김도윤")
        createEmployee(email = "zqab@dozycoffee.com", name = "zqab")

        val page = adapter.searchEmployeeRecords(EmployeeSearchCriteria(query = "zq_%"), PageRequest())

        assertEquals(setOf(byName.account.id, byEmail.account.id), page.items.map { it.id }.toSet())
        assertEquals(2L, page.totalElements)
    }

    @Test
    fun `상태와 id 조건을 함께 적용하고 생성 최신순으로 페이지를 나눔`() {
        val keyword = "검색${UUID.randomUUID().toString().take(8)}"
        val oldest = createEmployee(email = "1@dozycoffee.com", name = "${keyword}1", createdAt = NOW)
        val middle = createEmployee(email = "2@dozycoffee.com", name = "${keyword}2", createdAt = NOW.plusSeconds(1))
        val newest = createEmployee(email = "3@dozycoffee.com", name = "${keyword}3", createdAt = NOW.plusSeconds(2))
        adapter.changeStatus(middle.account.id, AccountStatus.PENDING, AccountStatus.ACTIVE, NOW)
        val all = EmployeeSearchCriteria(query = keyword)

        val first = adapter.searchEmployeeRecords(all, PageRequest(0, 2))
        val second = adapter.searchEmployeeRecords(all, PageRequest(1, 2))
        val pending = adapter.searchEmployeeRecords(all.copy(status = AccountStatus.PENDING), PageRequest())
        val byIds = adapter.searchEmployeeRecords(all.copy(principalIds = setOf(oldest.account.id, middle.account.id)), PageRequest())
        val noIds = adapter.searchEmployeeRecords(all.copy(principalIds = emptySet()), PageRequest())

        assertEquals(listOf(newest, middle).map { it.account.id }, first.items.map { it.id })
        assertEquals(listOf(oldest.account.id), second.items.map { it.id })
        assertEquals(3L, first.totalElements)
        assertEquals(2, first.totalPages)
        assertEquals(listOf(newest, oldest).map { it.account.id }, pending.items.map { it.id })
        assertEquals(listOf(middle, oldest).map { it.account.id }, byIds.items.map { it.id })
        assertEquals(0L, noIds.totalElements)
    }

    private fun createEmployee(
        email: String = "kim@dozycoffee.com",
        name: String = "김바리",
        createdAt: Instant = NOW,
    ): Employee = adapter.createEmployee(Email(email), name, null, null, createdAt)

    private fun principalRow(id: UUID) = PrincipalTable.selectAll().where { PrincipalTable.id eq id }.single()

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
        val LATER: Instant = NOW.plusSeconds(60)
    }
}
