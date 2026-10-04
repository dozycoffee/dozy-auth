package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.application.port.inbound.GrantRolesCommand
import com.dozycoffee.auth.server.application.port.inbound.GrantRolesUseCase
import com.dozycoffee.auth.server.application.port.inbound.RevokeRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.RevokeRoleUseCase
import com.dozycoffee.auth.starter.CurrentPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * principal의 role 부여·회수 (api/admin.md §4). 직원과 system client 모두에 쓰고, admin 임명·해임도 `auth:admin` 부여·회수로 합니다.
 *
 * 필요 role은 토큰의 role 이름으로 검사합니다 (GOV-14). 부여·회수 권한 규칙(GOV-02~07)의 관리 등급은 UseCase가 DB의 현재 role로 정합니다.
 */
@RestController
@PreAuthorize(AdminRoleController.OWNER_OR_ADMIN)
class AdminPrincipalRoleController(
    private val grantRoles: GrantRolesUseCase,
    private val revokeRole: RevokeRoleUseCase,
) {
    @PostMapping(ROLES_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun grantRoles(
        @PathVariable principalId: UUID,
        @Valid @RequestBody body: GrantRolesRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        grantRoles.grantRoles(
            GrantRolesCommand(
                manager = principal.key,
                principalId = principalId,
                roles = checkNotNull(body.roles).map { RoleCode.parse(checkNotNull(it)) }.toSet(),
                ip = client.ip,
                userAgent = client.userAgent,
            ),
        )
        return ResponseEntity.noContent().build()
    }

    /** 경로의 `role`은 `{audience}:{code}`입니다 (예: `/admin/principals/{principalId}/roles/wms:inbound_manager`). */
    @DeleteMapping(ROLE_PATH)
    fun revokeRole(
        @PathVariable principalId: UUID,
        @PathVariable @Pattern(regexp = ROLE_PATTERN) role: String,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        revokeRole.revokeRole(RevokeRoleCommand(principal.key, principalId, RoleCode.parse(role), client.ip, client.userAgent))
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val ROLES_PATH = "/admin/principals/{principalId}/roles"
        const val ROLE_PATH = "/admin/principals/{principalId}/roles/{role}"

        /** `{audience}:{code}`. 각각 DOM-03 형식입니다. */
        const val ROLE_PATTERN = "^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$"
    }
}

/**
 * role 부여 요청 (api/admin.md role 부여). 각 role은 `{audience}:{code}` 형식이어야 합니다.
 *
 * Kotlin은 목록 원소의 타입 주석을 Bean Validation이 읽을 수 있게 남기지 않으므로 원소 형식은 [isRolesWellFormed]로 검사합니다.
 */
data class GrantRolesRequest(
    @field:NotEmpty val roles: List<String?>?,
) {
    @get:AssertTrue(message = "role은 audience:code 형식이어야 합니다.")
    val isRolesWellFormed: Boolean
        get() = roles.orEmpty().all { it != null && RoleCode.parseOrNull(it) != null }
}
