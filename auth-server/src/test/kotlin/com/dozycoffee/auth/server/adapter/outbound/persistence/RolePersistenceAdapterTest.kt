package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.domain.authorization.Audience
import com.dozycoffee.auth.server.domain.authorization.AudienceCodeDuplicatedException
import com.dozycoffee.auth.server.domain.authorization.OwnerAlreadyAssignedException
import com.dozycoffee.auth.server.domain.authorization.Role
import com.dozycoffee.auth.server.domain.authorization.RoleCodeDuplicatedException
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.support.PersistenceAdapterTest
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** audience·role 정의와 role 부여의 저장 (docs/data-model.md §3.7~3.9, §4). */
@PersistenceAdapterTest
class RolePersistenceAdapterTest {
    private val adapter = RolePersistenceAdapter()

    @Test
    fun `seed의 audience와 system role을 조회함`() {
        assertEquals(listOf("wms", "catalog", "store", "auth"), adapter.findAudiences().map { it.code })

        val owner = adapter.findRoleByCode(RoleCode("auth", "owner"))
        val admin = adapter.findRoleByCode(RoleCode("auth", "admin"))
        assertEquals(1L, owner?.id)
        assertEquals(true, owner?.isSystem)
        assertEquals(2L, admin?.id)
        assertEquals(true, admin?.isSystem)
        assertEquals(listOf("auth:owner", "auth:admin"), adapter.findRoles().map { it.code.value })
    }

    @Test
    fun `audience를 등록하면 id와 code로 다시 조회함`() {
        val created = adapter.createAudience("order", "주문", null, NOW)

        assertEquals(Audience(created.id, "order", "주문", null), adapter.findAudienceById(created.id))
        assertEquals(created, adapter.findAudienceByCode("order"))
        assertNull(adapter.findAudienceByCode("unknown"))
    }

    @Test
    fun `같은 code로 audience를 등록하면 AUDIENCE_CODE_DUPLICATED`() {
        val error = assertFailsWith<AudienceCodeDuplicatedException> { adapter.createAudience("wms", "WMS 2", null, NOW) }

        assertEquals("AUDIENCE_CODE_DUPLICATED", error.code)
        assertEquals(409, error.status)
        assertEquals(4, adapter.findAudiences().size)
    }

    @Test
    fun `일반 role을 등록하면 id와 code로 다시 조회함`() {
        val created = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", "입고 등록", null, NOW)

        assertTrue(created.id >= 3, "일반 role의 id는 3부터")
        assertEquals(Role(created.id, RoleCode("wms", "inbound_manager"), "입고 관리자", "입고 등록", isSystem = false), created)
        assertEquals(created, adapter.findRoleById(created.id))
        assertEquals(created, adapter.findRoleByCode(RoleCode("wms", "inbound_manager")))
    }

    @Test
    fun `role을 등록하면 등록자와 시각을 남김`() {
        val creator = insertPrincipal()

        val created = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, creator, NOW)

        val row = RoleTable.selectAll().where { RoleTable.id eq created.id }.single()
        assertEquals(creator, row[RoleTable.createdBy])
        assertEquals(NOW, row[RoleTable.createdAt])
        assertEquals(NOW, row[RoleTable.updatedAt])
    }

    @Test
    fun `같은 audience에 같은 code로 role을 등록하면 ROLE_CODE_DUPLICATED`() {
        adapter.createRole(audience("wms"), "manager", "관리자", null, null, NOW)

        val error =
            assertFailsWith<RoleCodeDuplicatedException> { adapter.createRole(audience("wms"), "manager", "관리자 2", null, null, NOW) }

        assertEquals("ROLE_CODE_DUPLICATED", error.code)
        assertEquals(409, error.status)
        assertEquals(listOf("wms:manager"), adapter.findRoles("wms").map { it.code.value })
    }

    @Test
    fun `다른 audience에는 같은 code의 role을 등록할 수 있음`() {
        adapter.createRole(audience("wms"), "manager", "관리자", null, null, NOW)
        adapter.createRole(audience("catalog"), "manager", "관리자", null, null, NOW)

        assertEquals(listOf("wms:manager"), adapter.findRoles("wms").map { it.code.value })
        assertEquals(listOf("catalog:manager"), adapter.findRoles("catalog").map { it.code.value })
    }

    @Test
    fun `여러 code로 조회하면 정의된 role만 돌려줌`() {
        adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)

        val found =
            adapter.findRolesByCodes(
                listOf(RoleCode("wms", "inbound_manager"), RoleCode("auth", "admin"), RoleCode("wms", "unknown")),
            )

        assertEquals(setOf("wms:inbound_manager", "auth:admin"), found.map { it.code.value }.toSet())
        assertEquals(emptyList(), adapter.findRolesByCodes(emptyList()))
    }

    @Test
    fun `role의 이름과 설명만 바꾸고 수정 시각을 남김`() {
        val created = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", "입고 등록", null, NOW)
        val later = NOW.plusSeconds(60)

        val updated = adapter.updateDetails(created.id, "입고 담당", null, later)

        assertEquals(Role(created.id, RoleCode("wms", "inbound_manager"), "입고 담당", null, isSystem = false), updated)
        assertEquals(later, RoleTable.selectAll().where { RoleTable.id eq created.id }.single()[RoleTable.updatedAt])
    }

    @Test
    fun `없는 role을 수정하면 null`() {
        assertNull(adapter.updateDetails(Long.MAX_VALUE, "이름", null, NOW))
    }

    @Test
    fun `role을 삭제하면 더 이상 조회되지 않음`() {
        val created = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)

        assertTrue(adapter.deleteRole(created.id))
        assertNull(adapter.findRoleById(created.id))
        assertFalse(adapter.deleteRole(created.id))
    }

    @Test
    fun `role을 부여하면 부여자와 시각을 남김`() {
        val principal = insertPrincipal()
        val grantor = insertPrincipal()

        assertTrue(adapter.grant(RoleGrant(principal, ADMIN_ROLE_ID, grantor, NOW)))

        val row = grantRow(principal, ADMIN_ROLE_ID)
        assertEquals(grantor, row[PrincipalRoleTable.grantedBy])
        assertEquals(NOW, row[PrincipalRoleTable.grantedAt])
    }

    @Test
    fun `GOV-08 이미 가진 role을 부여하면 바꾸지 않고 성공`() {
        val principal = insertPrincipal()
        adapter.grant(RoleGrant(principal, ADMIN_ROLE_ID, null, NOW))

        val granted = adapter.grant(RoleGrant(principal, ADMIN_ROLE_ID, insertPrincipal(), NOW.plusSeconds(60)))

        assertFalse(granted)
        val row = grantRow(principal, ADMIN_ROLE_ID)
        assertNull(row[PrincipalRoleTable.grantedBy])
        assertEquals(NOW, row[PrincipalRoleTable.grantedAt])
    }

    @Test
    fun `GOV-08 가지지 않은 role을 회수해도 성공`() {
        val principal = insertPrincipal()

        assertFalse(adapter.revoke(principal, ADMIN_ROLE_ID))
    }

    @Test
    fun `가진 role을 회수하면 role 목록에서 빠짐`() {
        val principal = insertPrincipal()
        adapter.grant(RoleGrant(principal, ADMIN_ROLE_ID, null, NOW))

        assertTrue(adapter.revoke(principal, ADMIN_ROLE_ID))
        assertEquals(emptyList(), adapter.findRoleCodes(principal))
    }

    @Test
    fun `principal의 role을 audience와 code 순서로 돌려줌`() {
        val principal = insertPrincipal()
        val other = insertPrincipal()
        val inbound = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)
        val menu = adapter.createRole(audience("catalog"), "menu_editor", "메뉴 편집자", null, null, NOW)
        adapter.grant(RoleGrant(principal, inbound.id, null, NOW))
        adapter.grant(RoleGrant(principal, ADMIN_ROLE_ID, null, NOW))
        adapter.grant(RoleGrant(principal, menu.id, null, NOW))
        adapter.grant(RoleGrant(other, inbound.id, null, NOW))

        val roles = adapter.findRoleCodes(principal)

        assertEquals(listOf("auth:admin", "catalog:menu_editor", "wms:inbound_manager"), roles.map { it.value })
    }

    @Test
    fun `role이 없는 principal은 빈 role 목록`() {
        assertEquals(emptyList(), adapter.findRoleCodes(insertPrincipal()))
    }

    @Test
    fun `role을 가진 principal 수를 셈`() {
        val inbound = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)
        adapter.grant(RoleGrant(insertPrincipal(), inbound.id, null, NOW))
        adapter.grant(RoleGrant(insertPrincipal(), inbound.id, null, NOW))
        adapter.grant(RoleGrant(insertPrincipal(), ADMIN_ROLE_ID, null, NOW))

        assertEquals(2L, adapter.countHolders(inbound.id))
        assertEquals(
            mapOf(inbound.id to 2L, ADMIN_ROLE_ID to 1L, OWNER_ROLE_ID to 0L),
            adapter.countHolders(listOf(inbound.id, ADMIN_ROLE_ID, OWNER_ROLE_ID)),
        )
    }

    @Test
    fun `role을 모든 principal에게서 회수하면 회수한 principal을 돌려주고 삭제할 수 있음`() {
        val inbound = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)
        val first = insertPrincipal()
        val second = insertPrincipal()
        adapter.grant(RoleGrant(first, inbound.id, null, NOW))
        adapter.grant(RoleGrant(second, inbound.id, null, NOW))
        adapter.grant(RoleGrant(first, ADMIN_ROLE_ID, null, NOW))

        val revoked = adapter.revokeFromAll(inbound.id)

        assertEquals(setOf(first, second), revoked.toSet())
        assertEquals(0L, adapter.countHolders(inbound.id))
        assertEquals(listOf("auth:admin"), adapter.findRoleCodes(first).map { it.value })
        assertTrue(adapter.deleteRole(inbound.id))
    }

    @Test
    fun `여러 principal의 role을 한 번에 조회하고 role이 없으면 빈 목록`() {
        val first = insertPrincipal()
        val second = insertPrincipal()
        val inbound = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)
        adapter.grant(RoleGrant(first, inbound.id, null, NOW))
        adapter.grant(RoleGrant(first, ADMIN_ROLE_ID, null, NOW))

        val roles = adapter.findRoleCodes(listOf(first, second))

        assertEquals(
            mapOf(
                first to listOf("auth:admin", "wms:inbound_manager"),
                second to emptyList(),
            ),
            roles.mapValues { (_, codes) ->
                codes.map {
                    it.value
                }
            },
        )
        assertEquals(emptyMap(), adapter.findRoleCodes(emptyList()))
    }

    @Test
    fun `role을 가진 principal을 찾음`() {
        val inbound = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)
        val first = insertPrincipal()
        val second = insertPrincipal()
        adapter.grant(RoleGrant(first, inbound.id, null, NOW))
        adapter.grant(RoleGrant(second, inbound.id, null, NOW))
        adapter.grant(RoleGrant(insertPrincipal(), ADMIN_ROLE_ID, null, NOW))

        assertEquals(setOf(first, second), adapter.findHolderIds(inbound.id))
    }

    @Test
    fun `ACC-04 principal의 모든 role을 회수하고 다른 principal은 그대로`() {
        val inbound = adapter.createRole(audience("wms"), "inbound_manager", "입고 관리자", null, null, NOW)
        val principal = insertPrincipal()
        val other = insertPrincipal()
        adapter.grant(RoleGrant(principal, inbound.id, null, NOW))
        adapter.grant(RoleGrant(principal, ADMIN_ROLE_ID, null, NOW))
        adapter.grant(RoleGrant(other, inbound.id, null, NOW))

        assertEquals(2, adapter.revokeAll(principal))
        assertEquals(emptyList(), adapter.findRoleCodes(principal))
        assertEquals(listOf("wms:inbound_manager"), adapter.findRoleCodes(other).map { it.value })
    }

    @Test
    fun `GOV-10 다른 principal이 owner이면 auth owner 부여를 거부`() {
        val owner = insertPrincipal()
        val other = insertPrincipal()
        adapter.grant(RoleGrant(owner, OWNER_ROLE_ID, null, NOW))

        val error = assertFailsWith<OwnerAlreadyAssignedException> { adapter.grant(RoleGrant(other, OWNER_ROLE_ID, owner, NOW)) }

        assertEquals("INVALID_STATE", error.code)
        assertEquals(409, error.status)
        assertEquals(emptyList(), adapter.findRoleCodes(other))
        assertEquals(1L, adapter.countHolders(OWNER_ROLE_ID))
    }

    @Test
    fun `GOV-10 owner에게 auth owner를 다시 부여하면 바꾸지 않고 성공`() {
        val owner = insertPrincipal()
        adapter.grant(RoleGrant(owner, OWNER_ROLE_ID, null, NOW))

        assertFalse(adapter.grant(RoleGrant(owner, OWNER_ROLE_ID, null, NOW)))
    }

    @Test
    fun `GOV-10 owner를 회수한 뒤에는 다른 principal에게 부여할 수 있음`() {
        val owner = insertPrincipal()
        val next = insertPrincipal()
        adapter.grant(RoleGrant(owner, OWNER_ROLE_ID, null, NOW))

        adapter.revoke(owner, OWNER_ROLE_ID)

        assertTrue(adapter.grant(RoleGrant(next, OWNER_ROLE_ID, owner, NOW)))
        assertEquals(listOf("auth:owner"), adapter.findRoleCodes(next).map { it.value })
    }

    @Test
    fun `owner가 없으면 owner 조회는 null`() {
        assertNull(adapter.findOwnerId())
    }

    @Test
    fun `owner 조회는 auth owner를 가진 principal을 돌려줌`() {
        val owner = insertPrincipal()
        val admin = insertPrincipal()
        adapter.grant(RoleGrant(admin, ADMIN_ROLE_ID, null, NOW))
        adapter.grant(RoleGrant(owner, OWNER_ROLE_ID, null, NOW))

        assertEquals(owner, adapter.findOwnerId())
    }

    private fun audience(code: String): Audience = checkNotNull(adapter.findAudienceByCode(code))

    private fun grantRow(
        principalId: UUID,
        roleId: Long,
    ) = PrincipalRoleTable
        .selectAll()
        .where { (PrincipalRoleTable.principalId eq principalId) and (PrincipalRoleTable.roleId eq roleId) }
        .single()

    private fun insertPrincipal(): UUID =
        PrincipalTable
            .insertReturning(listOf(PrincipalTable.id)) {
                it[type] = "EMPLOYEE"
                it[status] = "ACTIVE"
            }.single()[PrincipalTable.id]

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")

        /** seed에서 고정한 system role id (docs/data-model.md §4) */
        const val OWNER_ROLE_ID = 1L
        const val ADMIN_ROLE_ID = 2L
    }
}
