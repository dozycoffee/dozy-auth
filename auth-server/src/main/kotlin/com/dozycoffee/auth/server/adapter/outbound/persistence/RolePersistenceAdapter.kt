package com.dozycoffee.auth.server.adapter.outbound.persistence

import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.AudienceTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.PrincipalRoleTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.table.RoleTable
import com.dozycoffee.auth.server.application.port.outbound.authorization.CountRoleHoldersPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CreateAudiencePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.CreateRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.DeleteRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.GrantRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadAudiencePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadOwnerPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadPrincipalRolesPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRoleHoldersPort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LoadRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.LockRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.RevokeRolePort
import com.dozycoffee.auth.server.application.port.outbound.authorization.UpdateRolePort
import com.dozycoffee.auth.server.domain.authorization.Audience
import com.dozycoffee.auth.server.domain.authorization.AudienceCodeDuplicatedException
import com.dozycoffee.auth.server.domain.authorization.OwnerAlreadyAssignedException
import com.dozycoffee.auth.server.domain.authorization.Role
import com.dozycoffee.auth.server.domain.authorization.RoleCodeDuplicatedException
import com.dozycoffee.auth.server.domain.authorization.RoleGrant
import com.dozycoffee.auth.server.domain.authorization.SystemRoles
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * audience·role 정의와 role 부여를 저장합니다 (docs/data-model.md §3.7~3.9).
 *
 * 트랜잭션은 열지 않고 호출한 UseCase의 트랜잭션 안에서 실행됩니다 (architecture.md §9).
 *
 * 유일성 위반은 `INSERT ... ON CONFLICT DO NOTHING`으로 처리합니다. 예외로 받으면 PostgreSQL이 트랜잭션 전체를 중단 상태로 만들기
 * 때문입니다. 넣은 행이 없으면 테이블마다 남은 유일성 제약이 하나뿐이라 원인을 구분할 수 있습니다.
 *
 * - `audience`: `code` UNIQUE → [AudienceCodeDuplicatedException]
 * - `role`: `(audience_id, code)` UNIQUE → [RoleCodeDuplicatedException]
 * - `principal_role`: 기본 키 `(principal_id, role_id)`이면 이미 가진 role(GOV-08), owner 유일성 인덱스이면
 *   [OwnerAlreadyAssignedException] (GOV-10). 넣은 행이 없을 때 그 부여가 실제로 있는지로 둘을 가릅니다.
 */
@Component
class RolePersistenceAdapter :
    LoadAudiencePort,
    CreateAudiencePort,
    LoadRolePort,
    LockRolePort,
    CreateRolePort,
    UpdateRolePort,
    DeleteRolePort,
    LoadPrincipalRolesPort,
    GrantRolePort,
    RevokeRolePort,
    CountRoleHoldersPort,
    LoadRoleHoldersPort,
    LoadOwnerPort {
    override fun findAudiences(): List<Audience> =
        AudienceTable
            .selectAll()
            .orderBy(AudienceTable.id)
            .map { it.toAudience() }

    override fun findAudienceById(id: Long): Audience? =
        AudienceTable
            .selectAll()
            .where { AudienceTable.id eq id }
            .singleOrNull()
            ?.toAudience()

    override fun findAudienceByCode(code: String): Audience? =
        AudienceTable
            .selectAll()
            .where { AudienceTable.code eq code }
            .singleOrNull()
            ?.toAudience()

    override fun createAudience(
        code: String,
        name: String,
        description: String?,
        createdAt: Instant,
    ): Audience {
        val id =
            AudienceTable
                .insertReturning(listOf(AudienceTable.id), ignoreErrors = true) {
                    it[AudienceTable.code] = code
                    it[AudienceTable.name] = name
                    it[AudienceTable.description] = description
                    it[AudienceTable.createdAt] = createdAt
                }.singleOrNull()
                ?.get(AudienceTable.id)
                ?: throw AudienceCodeDuplicatedException()
        return Audience(id, code, name, description)
    }

    override fun findRoles(audienceCode: String?): List<Role> =
        rolesWithAudience()
            .apply { if (audienceCode != null) where { AudienceTable.code eq audienceCode } }
            .orderBy(RoleTable.id)
            .map { it.toRole() }

    override fun findRoleById(id: Long): Role? = findRole { RoleTable.id eq id }

    override fun findRoleByCode(code: RoleCode): Role? =
        findRole { (AudienceTable.code eq code.audience) and (RoleTable.code eq code.code) }

    override fun findRolesByCodes(codes: Collection<RoleCode>): List<Role> {
        if (codes.isEmpty()) return emptyList()
        return rolesWithAudience()
            .where { (AudienceTable.code to RoleTable.code) inList codes.map { it.audience to it.code } }
            .orderBy(RoleTable.id)
            .map { it.toRole() }
    }

    // FOR KEY SHARE는 외래 키 검사와 같은 세기라 role 삭제(FOR UPDATE, DELETE)만 막고 이름·설명 수정은 막지 않습니다.
    // audience 행은 잠그지 않습니다(OF role).
    override fun lockRolesForGrant(codes: Collection<RoleCode>): List<Role> {
        if (codes.isEmpty()) return emptyList()
        return rolesWithAudience()
            .where { (AudienceTable.code to RoleTable.code) inList codes.map { it.audience to it.code } }
            .orderBy(RoleTable.id)
            .forUpdate(ForUpdateOption.PostgreSQL.ForKeyShare(ofTables = arrayOf(RoleTable)))
            .map { it.toRole() }
    }

    override fun lockRoleForDelete(id: Long): Role? =
        rolesWithAudience()
            .where { RoleTable.id eq id }
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ofTables = arrayOf(RoleTable)))
            .singleOrNull()
            ?.toRole()

    override fun createRole(
        audience: Audience,
        code: String,
        name: String,
        description: String?,
        createdBy: UUID?,
        createdAt: Instant,
    ): Role {
        val id =
            RoleTable
                .insertReturning(listOf(RoleTable.id), ignoreErrors = true) {
                    it[RoleTable.audienceId] = audience.id
                    it[RoleTable.code] = code
                    it[RoleTable.name] = name
                    it[RoleTable.description] = description
                    it[RoleTable.createdBy] = createdBy
                    it[RoleTable.createdAt] = createdAt
                    it[RoleTable.updatedAt] = createdAt
                }.singleOrNull()
                ?.get(RoleTable.id)
                ?: throw RoleCodeDuplicatedException()
        return Role(id, RoleCode(audience.code, code), name, description, isSystem = false)
    }

    override fun updateDetails(
        id: Long,
        name: String,
        description: String?,
        updatedAt: Instant,
    ): Role? {
        val updated =
            RoleTable.update({ RoleTable.id eq id }) {
                it[RoleTable.name] = name
                it[RoleTable.description] = description
                it[RoleTable.updatedAt] = updatedAt
            }
        return if (updated == 0) null else findRoleById(id)
    }

    override fun deleteRole(id: Long): Boolean = RoleTable.deleteWhere { RoleTable.id eq id } > 0

    override fun findRoleCodes(principalId: UUID): List<RoleCode> = findRoleCodes(listOf(principalId)).getValue(principalId)

    override fun findRoleCodes(principalIds: Collection<UUID>): Map<UUID, List<RoleCode>> {
        if (principalIds.isEmpty()) return emptyMap()
        val codes =
            PrincipalRoleTable
                .join(RoleTable, JoinType.INNER, PrincipalRoleTable.roleId, RoleTable.id)
                .join(AudienceTable, JoinType.INNER, RoleTable.audienceId, AudienceTable.id)
                .select(PrincipalRoleTable.principalId, AudienceTable.code, RoleTable.code)
                .where { PrincipalRoleTable.principalId inList principalIds }
                .orderBy(AudienceTable.code to SortOrder.ASC, RoleTable.code to SortOrder.ASC)
                .groupBy({ it[PrincipalRoleTable.principalId] }, { RoleCode(it[AudienceTable.code], it[RoleTable.code]) })
        return principalIds.associateWith { codes[it].orEmpty() }
    }

    override fun grant(grant: RoleGrant): Boolean {
        val inserted =
            PrincipalRoleTable
                .insertReturning(listOf(PrincipalRoleTable.roleId), ignoreErrors = true) {
                    it[PrincipalRoleTable.principalId] = grant.principalId
                    it[PrincipalRoleTable.roleId] = grant.roleId
                    it[PrincipalRoleTable.grantedBy] = grant.grantedBy
                    it[PrincipalRoleTable.grantedAt] = grant.grantedAt
                }.any()
        if (inserted) return true
        // GOV-08 이미 가진 role이면 성공. 가지지 않았는데 들어가지 않았으면 owner 유일성 인덱스가 막은 것 (GOV-10)
        if (holds(grant.principalId, grant.roleId)) return false
        throw OwnerAlreadyAssignedException()
    }

    override fun revoke(
        principalId: UUID,
        roleId: Long,
    ): Boolean =
        PrincipalRoleTable.deleteWhere {
            (PrincipalRoleTable.principalId eq principalId) and (PrincipalRoleTable.roleId eq roleId)
        } > 0

    override fun revokeFromAll(roleId: Long): List<UUID> =
        PrincipalRoleTable
            .deleteReturning(listOf(PrincipalRoleTable.principalId)) { PrincipalRoleTable.roleId eq roleId }
            .map { it[PrincipalRoleTable.principalId] }

    override fun revokeAll(principalId: UUID): Int = PrincipalRoleTable.deleteWhere { PrincipalRoleTable.principalId eq principalId }

    override fun findHolderIds(roleId: Long): Set<UUID> =
        PrincipalRoleTable
            .select(PrincipalRoleTable.principalId)
            .where { PrincipalRoleTable.roleId eq roleId }
            .mapTo(mutableSetOf()) { it[PrincipalRoleTable.principalId] }

    override fun countHolders(roleId: Long): Long = countHolders(listOf(roleId)).getValue(roleId)

    override fun countHolders(roleIds: Collection<Long>): Map<Long, Long> {
        if (roleIds.isEmpty()) return emptyMap()
        val holders = PrincipalRoleTable.principalId.count()
        val counts =
            PrincipalRoleTable
                .select(PrincipalRoleTable.roleId, holders)
                .where { PrincipalRoleTable.roleId inList roleIds }
                .groupBy(PrincipalRoleTable.roleId)
                .associate { it[PrincipalRoleTable.roleId] to it[holders] }
        return roleIds.associateWith { counts[it] ?: 0L }
    }

    override fun findOwnerId(): UUID? =
        PrincipalRoleTable
            .join(RoleTable, JoinType.INNER, PrincipalRoleTable.roleId, RoleTable.id)
            .join(AudienceTable, JoinType.INNER, RoleTable.audienceId, AudienceTable.id)
            .select(PrincipalRoleTable.principalId)
            .where { (AudienceTable.code eq SystemRoles.OWNER.audience) and (RoleTable.code eq SystemRoles.OWNER.code) }
            .singleOrNull()
            ?.get(PrincipalRoleTable.principalId)

    private fun holds(
        principalId: UUID,
        roleId: Long,
    ): Boolean =
        PrincipalRoleTable
            .select(PrincipalRoleTable.roleId)
            .where { (PrincipalRoleTable.principalId eq principalId) and (PrincipalRoleTable.roleId eq roleId) }
            .any()

    private fun rolesWithAudience(): Query =
        RoleTable
            .join(AudienceTable, JoinType.INNER, RoleTable.audienceId, AudienceTable.id)
            .selectAll()

    private fun findRole(condition: () -> Op<Boolean>): Role? =
        rolesWithAudience()
            .where(condition)
            .singleOrNull()
            ?.toRole()

    private fun ResultRow.toAudience() =
        Audience(
            id = this[AudienceTable.id],
            code = this[AudienceTable.code],
            name = this[AudienceTable.name],
            description = this[AudienceTable.description],
        )

    private fun ResultRow.toRole() =
        Role(
            id = this[RoleTable.id],
            code = RoleCode(this[AudienceTable.code], this[RoleTable.code]),
            name = this[RoleTable.name],
            description = this[RoleTable.description],
            isSystem = this[RoleTable.isSystem],
        )
}
