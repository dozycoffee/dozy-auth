package com.dozycoffee.auth.server.adapter.inbound.startup

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.application.port.outbound.account.LoadEmployeePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadOwnerPort
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.Employee
import com.dozycoffee.auth.server.support.TestEmployees
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals

/**
 * GOV-11 기동이 끝나면 부트스트랩이 실행됨. test 프로필은 부트스트랩을 끄므로 이 테스트에서만 켭니다.
 * 이 컨텍스트가 기동할 때 owner를 만들므로, 끝나면 지워 다른 테스트에 남기지 않습니다.
 */
@SpringBootTest(
    properties = [
        "dozy.auth.bootstrap.enabled=true",
        "dozy.auth.bootstrap.owner-email=${OwnerBootstrapStartupTest.OWNER_EMAIL}",
    ],
)
@Import(TestcontainersConfiguration::class, TestEmployees::class)
@ActiveProfiles("test")
class OwnerBootstrapStartupTest {
    @Autowired
    lateinit var employees: TestEmployees

    @Autowired
    lateinit var loadOwner: LoadOwnerPort

    @Autowired
    lateinit var loadEmployee: LoadEmployeePort

    @AfterEach
    fun cleanUp() {
        employees.inTransaction { loadOwner.findOwnerId()?.let(employees::track) ?: Unit }
        employees.cleanUp()
    }

    @Test
    fun `GOV-11 기동하면 설정 이메일로 owner를 초대함`() {
        val owner: Employee =
            employees.inTransaction { checkNotNull(loadOwner.findOwnerId()?.let(loadEmployee::findEmployeeById)) }

        assertEquals(OWNER_EMAIL, owner.profile.email.value)
        assertEquals(AccountStatus.PENDING, owner.account.status)
        assertEquals(listOf("EMPLOYEE_INVITED", "ROLE_GRANTED"), employees.auditActions(owner.account.id))
    }

    companion object {
        const val OWNER_EMAIL = "startup-owner@dozycoffee.test"
    }
}
