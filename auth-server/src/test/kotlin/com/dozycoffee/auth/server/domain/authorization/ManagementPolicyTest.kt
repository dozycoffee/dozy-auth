package com.dozycoffee.auth.server.domain.authorization

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.AuthException
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** owner·admin 보호 규칙. GOV-02~08, GOV-13, GOV-15. 기대 code는 api/conventions.md §11의 문자열입니다. */
class ManagementPolicyTest {
    // 관리 등급 × 대상 × 작업 표

    @TestFactory
    fun `GOV-02 GOV-03 계정 변경 작업 표`(): List<DynamicTest> =
        ManagementAction.entries.flatMap { action ->
            val ownerSelf = if (action.disablesAccount) PROTECTED else null
            listOf(
                Row(OWNER, OWNER_TARGET, ownerSelf, "owner가 자기 자신"),
                Row(OWNER, OTHER_ADMIN_TARGET, null, "owner가 admin"),
                Row(OWNER, EMPLOYEE_TARGET, null, "owner가 일반 직원"),
                Row(ADMIN, ADMIN_TARGET, PROTECTED, "admin이 자기 자신"),
                Row(ADMIN, OWNER_TARGET, PROTECTED, "admin이 owner"),
                Row(ADMIN, OTHER_ADMIN_TARGET, PROTECTED, "admin이 다른 admin"),
                Row(ADMIN, EMPLOYEE_TARGET, null, "admin이 일반 직원"),
                Row(NO_GRADE, EMPLOYEE_TARGET, FORBIDDEN, "관리 등급 없는 직원이 일반 직원"),
            ).map { row ->
                dynamicTest("$action: ${row.description}이면 ${row.expected ?: "허용"}") {
                    assertOutcome(row.expected) { ManagementPolicy.checkCanManage(row.manager, row.target, action) }
                }
            }
        }

    @TestFactory
    fun `GOV-04 GOV-05 GOV-02 role 부여 표`(): List<DynamicTest> =
        listOf(
            Row(OWNER, OWNER_TARGET, SELF_GRANT, "owner가 자기 자신에게 일반 role", GENERAL_ROLE),
            Row(OWNER, OWNER_TARGET, SELF_GRANT, "owner가 자기 자신에게 auth:admin", SystemRoles.ADMIN),
            Row(OWNER, OWNER_TARGET, FORBIDDEN, "owner가 자기 자신에게 auth:owner", SystemRoles.OWNER),
            Row(OWNER, OTHER_ADMIN_TARGET, null, "owner가 admin에게 일반 role", GENERAL_ROLE),
            Row(OWNER, OTHER_ADMIN_TARGET, null, "owner가 admin에게 auth:admin", SystemRoles.ADMIN),
            Row(OWNER, OTHER_ADMIN_TARGET, FORBIDDEN, "owner가 admin에게 auth:owner", SystemRoles.OWNER),
            Row(OWNER, EMPLOYEE_TARGET, null, "owner가 일반 직원에게 일반 role", GENERAL_ROLE),
            Row(OWNER, EMPLOYEE_TARGET, null, "owner가 일반 직원에게 auth:admin", SystemRoles.ADMIN),
            Row(OWNER, EMPLOYEE_TARGET, FORBIDDEN, "owner가 일반 직원에게 auth:owner", SystemRoles.OWNER),
            Row(ADMIN, ADMIN_TARGET, SELF_GRANT, "admin이 자기 자신에게 일반 role", GENERAL_ROLE),
            Row(ADMIN, ADMIN_TARGET, FORBIDDEN, "admin이 자기 자신에게 auth:admin", SystemRoles.ADMIN),
            Row(ADMIN, ADMIN_TARGET, FORBIDDEN, "admin이 자기 자신에게 auth:owner", SystemRoles.OWNER),
            Row(ADMIN, OWNER_TARGET, PROTECTED, "admin이 owner에게 일반 role", GENERAL_ROLE),
            Row(ADMIN, OWNER_TARGET, FORBIDDEN, "admin이 owner에게 auth:admin", SystemRoles.ADMIN),
            Row(ADMIN, OTHER_ADMIN_TARGET, PROTECTED, "admin이 다른 admin에게 일반 role", GENERAL_ROLE),
            Row(ADMIN, OTHER_ADMIN_TARGET, FORBIDDEN, "admin이 다른 admin에게 auth:owner", SystemRoles.OWNER),
            Row(ADMIN, EMPLOYEE_TARGET, null, "admin이 일반 직원에게 일반 role", GENERAL_ROLE),
            Row(ADMIN, EMPLOYEE_TARGET, FORBIDDEN, "admin이 일반 직원에게 auth:admin", SystemRoles.ADMIN),
            Row(ADMIN, EMPLOYEE_TARGET, FORBIDDEN, "admin이 일반 직원에게 auth:owner", SystemRoles.OWNER),
            Row(NO_GRADE, EMPLOYEE_TARGET, FORBIDDEN, "관리 등급 없는 직원이 일반 직원에게 일반 role", GENERAL_ROLE),
        ).map { row ->
            dynamicTest("${row.description}이면 ${row.expected ?: "허용"}") {
                assertOutcome(row.expected) { ManagementPolicy.checkCanGrant(row.manager, row.target, listOf(row.role!!)) }
            }
        }

    @TestFactory
    fun `GOV-05 GOV-02 role 회수 표`(): List<DynamicTest> =
        listOf(
            Row(OWNER, OWNER_TARGET, null, "owner가 자기 자신에게서 일반 role", GENERAL_ROLE),
            Row(OWNER, OWNER_TARGET, FORBIDDEN, "owner가 자기 자신에게서 auth:owner", SystemRoles.OWNER),
            Row(OWNER, OTHER_ADMIN_TARGET, null, "owner가 admin에게서 일반 role", GENERAL_ROLE),
            Row(OWNER, OTHER_ADMIN_TARGET, null, "owner가 admin에게서 auth:admin", SystemRoles.ADMIN),
            Row(OWNER, EMPLOYEE_TARGET, null, "owner가 일반 직원에게서 일반 role", GENERAL_ROLE),
            Row(ADMIN, ADMIN_TARGET, PROTECTED, "admin이 자기 자신에게서 일반 role", GENERAL_ROLE),
            Row(ADMIN, ADMIN_TARGET, FORBIDDEN, "admin이 자기 자신에게서 auth:admin", SystemRoles.ADMIN),
            Row(ADMIN, OWNER_TARGET, PROTECTED, "admin이 owner에게서 일반 role", GENERAL_ROLE),
            Row(ADMIN, OWNER_TARGET, FORBIDDEN, "admin이 owner에게서 auth:owner", SystemRoles.OWNER),
            Row(ADMIN, OTHER_ADMIN_TARGET, PROTECTED, "admin이 다른 admin에게서 일반 role", GENERAL_ROLE),
            Row(ADMIN, OTHER_ADMIN_TARGET, FORBIDDEN, "admin이 다른 admin에게서 auth:admin", SystemRoles.ADMIN),
            Row(ADMIN, EMPLOYEE_TARGET, null, "admin이 일반 직원에게서 일반 role", GENERAL_ROLE),
            Row(NO_GRADE, EMPLOYEE_TARGET, FORBIDDEN, "관리 등급 없는 직원이 일반 직원에게서 일반 role", GENERAL_ROLE),
        ).map { row ->
            dynamicTest("${row.description}이면 ${row.expected ?: "허용"}") {
                assertOutcome(row.expected) { ManagementPolicy.checkCanRevoke(row.manager, row.target, row.role!!) }
            }
        }

    // GOV-03

    @Test
    fun `GOV-03 owner 계정은 정지·비활성화·초대 취소를 누구도 할 수 없음`() {
        val disabling = ManagementAction.entries.filter { it.disablesAccount }

        assertEquals(setOf(ManagementAction.SUSPEND, ManagementAction.DEACTIVATE, ManagementAction.CANCEL_INVITATION), disabling.toSet())
        disabling.forEach { action ->
            assertCode(PROTECTED) { ManagementPolicy.checkCanManage(OWNER, OWNER_TARGET, action) }
            assertCode(PROTECTED) { ManagementPolicy.checkCanManage(ADMIN, OWNER_TARGET, action) }
        }
    }

    // GOV-05

    @Test
    fun `GOV-05 여러 role 중 하나라도 owner가 아닌데 auth admin이면 전체 거부`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanGrant(ADMIN, EMPLOYEE_TARGET, listOf(GENERAL_ROLE, SystemRoles.ADMIN)) }
    }

    // 직원 초대

    @Test
    fun `직원 초대는 owner·admin이 role 없이 하거나 일반 role을 지정해 할 수 있음`() {
        listOf(OWNER, ADMIN).forEach { manager ->
            ManagementPolicy.checkCanInvite(manager, emptyList())
            ManagementPolicy.checkCanInvite(manager, listOf(GENERAL_ROLE, RoleCode("auth", "partner_reader")))
        }
    }

    @Test
    fun `관리 등급이 없으면 role 없는 직원 초대도 FORBIDDEN`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanInvite(NO_GRADE, emptyList()) }
    }

    @Test
    fun `GOV-05 직원 초대에서 auth admin은 owner만 지정하고 auth owner는 누구도 지정할 수 없음`() {
        ManagementPolicy.checkCanInvite(OWNER, listOf(SystemRoles.ADMIN))
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanInvite(ADMIN, listOf(GENERAL_ROLE, SystemRoles.ADMIN)) }
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanInvite(OWNER, listOf(SystemRoles.OWNER)) }
    }

    // system client 등록

    @Test
    fun `system client 등록은 owner·admin이 role 없이 하거나 일반 role을 지정해 할 수 있음`() {
        listOf(OWNER, ADMIN).forEach { manager ->
            ManagementPolicy.checkCanRegisterSystemClient(manager, emptyList())
            ManagementPolicy.checkCanRegisterSystemClient(manager, listOf(GENERAL_ROLE, RoleCode("auth", "partner_reader")))
        }
    }

    @Test
    fun `관리 등급이 없으면 role 없는 system client 등록도 FORBIDDEN`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanRegisterSystemClient(NO_GRADE, emptyList()) }
    }

    @Test
    fun `GOV-06 owner라도 system client 등록에서 system role을 지정하면 FORBIDDEN`() {
        listOf(SystemRoles.ADMIN, SystemRoles.OWNER).forEach { role ->
            assertCode(FORBIDDEN) { ManagementPolicy.checkCanRegisterSystemClient(OWNER, listOf(GENERAL_ROLE, role)) }
            assertCode(FORBIDDEN) { ManagementPolicy.checkCanRegisterSystemClient(ADMIN, listOf(role)) }
        }
    }

    // GOV-06

    @Test
    fun `GOV-06 system client에는 일반 role을 부여할 수 있음`() {
        ManagementPolicy.checkCanGrant(ADMIN, SYSTEM_TARGET, listOf(GENERAL_ROLE, RoleCode("auth", "partner_reader")))
    }

    @Test
    fun `GOV-06 owner라도 system client에 auth admin을 부여하면 FORBIDDEN`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanGrant(OWNER, SYSTEM_TARGET, listOf(SystemRoles.ADMIN)) }
    }

    @Test
    fun `GOV-06 파트너에게는 어떤 role도 부여할 수 없음`() {
        assertCode(INVALID_STATE) { ManagementPolicy.checkCanGrant(OWNER, PARTNER_TARGET, listOf(GENERAL_ROLE)) }
        assertCode(INVALID_STATE) { ManagementPolicy.checkCanGrant(ADMIN, PARTNER_TARGET, listOf(RoleCode("store", "viewer"))) }
    }

    @Test
    fun `GOV-06 고객에게는 role을 부여할 수 없음`() {
        assertCode(INVALID_STATE) { ManagementPolicy.checkCanGrant(OWNER, target(PrincipalType.CUSTOMER), listOf(GENERAL_ROLE)) }
    }

    @Test
    fun `GOV-06 파트너 계정 변경과 role 회수는 막지 않음`() {
        ManagementPolicy.checkCanManage(ADMIN, PARTNER_TARGET, ManagementAction.SUSPEND)
        ManagementPolicy.checkCanRevoke(ADMIN, PARTNER_TARGET, GENERAL_ROLE)
    }

    // GOV-07

    @Test
    fun `GOV-07 PENDING·ACTIVE 대상에게는 role을 부여할 수 있음`() {
        ManagementPolicy.checkCanGrant(ADMIN, EMPLOYEE_TARGET.copy(status = TargetStatus.PENDING), listOf(GENERAL_ROLE))
        ManagementPolicy.checkCanGrant(ADMIN, EMPLOYEE_TARGET.copy(status = TargetStatus.ACTIVE), listOf(GENERAL_ROLE))
        ManagementPolicy.checkCanGrant(ADMIN, SYSTEM_TARGET, listOf(GENERAL_ROLE))
    }

    @Test
    fun `GOV-07 SUSPENDED·DEACTIVATED 대상에게 부여하면 INVALID_STATE`() {
        listOf(TargetStatus.SUSPENDED, TargetStatus.DEACTIVATED).forEach { status ->
            assertCode(INVALID_STATE) { ManagementPolicy.checkCanGrant(ADMIN, EMPLOYEE_TARGET.copy(status = status), listOf(GENERAL_ROLE)) }
            assertCode(INVALID_STATE) { ManagementPolicy.checkCanGrant(OWNER, SYSTEM_TARGET.copy(status = status), listOf(GENERAL_ROLE)) }
        }
    }

    @Test
    fun `GOV-07 SUSPENDED 대상에게서 회수는 할 수 있음`() {
        ManagementPolicy.checkCanRevoke(ADMIN, EMPLOYEE_TARGET.copy(status = TargetStatus.SUSPENDED), GENERAL_ROLE)
    }

    // GOV-13

    @Test
    fun `GOV-13 system role 정의는 owner도 수정·삭제할 수 없음`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanModifyRoleDefinition(OWNER, role(SystemRoles.OWNER, isSystem = true)) }
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanModifyRoleDefinition(OWNER, role(SystemRoles.ADMIN, isSystem = true)) }
    }

    @Test
    fun `GOV-13 owner와 admin은 일반 role 정의를 등록·수정·삭제할 수 있음`() {
        listOf(OWNER, ADMIN).forEach { manager ->
            ManagementPolicy.checkCanDefineRole(manager)
            ManagementPolicy.checkCanModifyRoleDefinition(manager, role(GENERAL_ROLE, isSystem = false))
            ManagementPolicy.checkCanModifyRoleDefinition(manager, role(RoleCode("auth", "partner_reader"), isSystem = false))
        }
    }

    @Test
    fun `GOV-13 관리 등급이 없으면 role 정의를 등록·수정·삭제할 수 없음`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanDefineRole(NO_GRADE) }
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanModifyRoleDefinition(NO_GRADE, role(GENERAL_ROLE, isSystem = false)) }
    }

    @Test
    fun `GOV-13 audience 추가는 owner만 할 수 있음`() {
        ManagementPolicy.checkCanCreateAudience(OWNER)
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanCreateAudience(ADMIN) }
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanCreateAudience(NO_GRADE) }
    }

    // GOV-15

    @Test
    fun `GOV-15 role 부여는 GOV-05를 GOV-04보다 먼저 검사`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanGrant(ADMIN, ADMIN_TARGET, listOf(SystemRoles.ADMIN)) }
    }

    @Test
    fun `GOV-15 role 부여는 GOV-04를 GOV-02보다 먼저 검사`() {
        assertCode(SELF_GRANT) { ManagementPolicy.checkCanGrant(ADMIN, ADMIN_TARGET, listOf(GENERAL_ROLE)) }
    }

    @Test
    fun `GOV-15 role 부여는 GOV-02를 GOV-07보다 먼저 검사`() {
        val suspendedAdmin = OTHER_ADMIN_TARGET.copy(status = TargetStatus.SUSPENDED)

        assertCode(PROTECTED) { ManagementPolicy.checkCanGrant(ADMIN, suspendedAdmin, listOf(GENERAL_ROLE)) }
    }

    @Test
    fun `GOV-15 role 부여는 GOV-05를 GOV-06보다 먼저 검사`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanGrant(ADMIN, PARTNER_TARGET, listOf(SystemRoles.ADMIN)) }
    }

    @Test
    fun `GOV-15 role 부여는 GOV-06을 GOV-07보다 먼저 검사`() {
        val suspendedClient = SYSTEM_TARGET.copy(status = TargetStatus.SUSPENDED)

        assertCode(FORBIDDEN) { ManagementPolicy.checkCanGrant(OWNER, suspendedClient, listOf(SystemRoles.ADMIN)) }
    }

    @Test
    fun `GOV-15 role 회수는 GOV-05를 GOV-02보다 먼저 검사`() {
        assertCode(FORBIDDEN) { ManagementPolicy.checkCanRevoke(ADMIN, OWNER_TARGET, SystemRoles.ADMIN) }
    }

    // 관리 등급

    @Test
    fun `auth owner를 가지면 owner 등급`() {
        assertEquals(AdminGrade.OWNER, AdminGrade.of(listOf(RoleCode("auth", "owner"))))
        assertEquals(AdminGrade.OWNER, AdminGrade.of(listOf(RoleCode("auth", "admin"), RoleCode("auth", "owner"), GENERAL_ROLE)))
    }

    @Test
    fun `auth owner 없이 auth admin을 가지면 admin 등급`() {
        assertEquals(AdminGrade.ADMIN, AdminGrade.of(listOf(GENERAL_ROLE, RoleCode("auth", "admin"))))
    }

    @Test
    fun `system role이 없으면 관리 등급 없음`() {
        assertEquals(AdminGrade.NONE, AdminGrade.of(emptyList()))
        assertEquals(AdminGrade.NONE, AdminGrade.of(listOf(GENERAL_ROLE, RoleCode("auth", "partner_reader"), RoleCode("wms", "admin"))))
    }

    @Test
    fun `부여할 role이 없으면 호출 오류`() {
        assertFailsWith<IllegalArgumentException> { ManagementPolicy.checkCanGrant(OWNER, EMPLOYEE_TARGET, emptyList()) }
    }

    private data class Row(
        val manager: Manager,
        val target: ManagedTarget,
        val expected: String?,
        val description: String,
        val role: RoleCode? = null,
    )

    private fun assertOutcome(
        expected: String?,
        block: () -> Unit,
    ) {
        if (expected == null) block() else assertCode(expected, block)
    }

    private fun assertCode(
        expected: String,
        block: () -> Unit,
    ) {
        assertEquals(expected, assertFailsWith<AuthException> { block() }.code)
    }

    private companion object {
        const val PROTECTED = "PROTECTED_ACCOUNT"
        const val SELF_GRANT = "SELF_GRANT_NOT_ALLOWED"
        const val FORBIDDEN = "FORBIDDEN"
        const val INVALID_STATE = "INVALID_STATE"

        val GENERAL_ROLE = RoleCode("wms", "inbound_manager")

        val OWNER_ID: UUID = UUID.fromString("0199a3b0-2c7d-7e41-a5f8-3b0c9d6e1f24")
        val ADMIN_ID: UUID = UUID.fromString("0199a3b1-4d2e-7a10-8b3c-1f2e3d4c5b6a")
        val OTHER_ADMIN_ID: UUID = UUID.fromString("0199a3b2-6f1a-7c20-9d4e-2a3b4c5d6e7f")
        val NO_GRADE_ID: UUID = UUID.fromString("0199a3b3-8a2b-7d30-a5f6-3b4c5d6e7f80")

        val OWNER = Manager(OWNER_ID, AdminGrade.OWNER)
        val ADMIN = Manager(ADMIN_ID, AdminGrade.ADMIN)
        val NO_GRADE = Manager(NO_GRADE_ID, AdminGrade.NONE)

        val OWNER_TARGET = target(PrincipalType.EMPLOYEE, AdminGrade.OWNER, OWNER_ID)
        val ADMIN_TARGET = target(PrincipalType.EMPLOYEE, AdminGrade.ADMIN, ADMIN_ID)
        val OTHER_ADMIN_TARGET = target(PrincipalType.EMPLOYEE, AdminGrade.ADMIN, OTHER_ADMIN_ID)
        val EMPLOYEE_TARGET = target(PrincipalType.EMPLOYEE)
        val SYSTEM_TARGET = target(PrincipalType.SYSTEM, id = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73"))
        val PARTNER_TARGET = target(PrincipalType.PARTNER, id = UUID.fromString("0199a3c5-1d4f-7a8b-b2c6-5e9f0a3d7c21"))

        fun target(
            type: PrincipalType,
            grade: AdminGrade = AdminGrade.NONE,
            id: UUID = UUID.fromString("0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f"),
            status: TargetStatus = TargetStatus.ACTIVE,
        ) = ManagedTarget(id, type, grade, status)

        fun role(
            code: RoleCode,
            isSystem: Boolean,
        ) = Role(1, code, code.value, null, isSystem)
    }
}
