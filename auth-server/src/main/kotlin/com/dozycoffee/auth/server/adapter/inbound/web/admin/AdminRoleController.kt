package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.application.port.inbound.admin.CreateAudienceCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.CreateAudienceUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.DefineRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.DefineRoleUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.DeleteRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.DeleteRoleUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.ListAudiencesUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.ListRolesUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.RoleDefinition
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateRoleCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateRoleUseCase
import com.dozycoffee.auth.server.domain.authorization.Audience
import com.dozycoffee.auth.starter.CurrentPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Null
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * role 정의와 audience 관리 (api/admin.md §5).
 *
 * 토큰 검증(`aud`에 `auth` 포함)과 principal type 검사는 보안 설정(`/admin/...` 체인)이 먼저 합니다 (api/conventions.md §2).
 * 엔드포인트별 필요 role은 여기서 role 이름으로 검사합니다 (GOV-14). 스타터는 `auth` audience의 role을 `ROLE_{code}` 권한으로
 * 바꾸므로 `auth:owner`는 `ROLE_owner`입니다. 메서드에 붙인 검사가 클래스의 검사보다 우선합니다.
 * 변경 작업은 UseCase가 DB의 현재 role로 관리 등급을 다시 확인합니다 (GOV-14).
 */
@RestController
@PreAuthorize(AdminRoleController.OWNER_OR_ADMIN)
class AdminRoleController(
    private val listRoles: ListRolesUseCase,
    private val defineRole: DefineRoleUseCase,
    private val updateRole: UpdateRoleUseCase,
    private val deleteRole: DeleteRoleUseCase,
    private val listAudiences: ListAudiencesUseCase,
    private val createAudience: CreateAudienceUseCase,
) {
    @GetMapping(ROLES_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listRoles(
        @RequestParam(required = false) audience: String?,
    ): ItemsResponse<RoleResponse> = ItemsResponse(listRoles.listRoles(audience).map(RoleResponse::of))

    @PostMapping(ROLES_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun defineRole(
        @Valid @RequestBody body: DefineRoleRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<RoleResponse> {
        val client = ClientInfo.of(request)
        val defined =
            defineRole.defineRole(
                DefineRoleCommand(
                    manager = principal.key,
                    audienceCode = checkNotNull(body.audience),
                    code = checkNotNull(body.code),
                    name = checkNotNull(body.name),
                    description = body.description?.ifEmpty { null },
                    ip = client.ip,
                    userAgent = client.userAgent,
                ),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(RoleResponse.of(defined))
    }

    @PatchMapping(ROLE_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun updateRole(
        @PathVariable roleId: Long,
        @Valid @RequestBody body: UpdateRoleRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): RoleResponse {
        val client = ClientInfo.of(request)
        val updated =
            updateRole.updateRole(
                UpdateRoleCommand(
                    manager = principal.key,
                    roleId = roleId,
                    name = body.name,
                    description = body.description,
                    ip = client.ip,
                    userAgent = client.userAgent,
                ),
            )
        return RoleResponse.of(updated)
    }

    /** `revokeAll=true`는 앱이 `ROLE_IN_USE`를 받고 영향 인원을 확인받은 뒤 보내는 요청입니다. */
    @DeleteMapping(ROLE_PATH)
    fun deleteRole(
        @PathVariable roleId: Long,
        @RequestParam(defaultValue = "false") revokeAll: Boolean,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        deleteRole.deleteRole(DeleteRoleCommand(principal.key, roleId, revokeAll, client.ip, client.userAgent))
        return ResponseEntity.noContent().build()
    }

    @GetMapping(AUDIENCES_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listAudiences(): ItemsResponse<AudienceResponse> = ItemsResponse(listAudiences.listAudiences().map(AudienceResponse::of))

    /** GOV-13 audience 추가는 owner만 합니다. 토큰으로 먼저 거르고, UseCase가 DB의 role로 다시 확인합니다. */
    @PreAuthorize(OWNER)
    @PostMapping(AUDIENCES_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createAudience(
        @Valid @RequestBody body: CreateAudienceRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AudienceResponse> {
        val client = ClientInfo.of(request)
        val created =
            createAudience.createAudience(
                CreateAudienceCommand(
                    manager = principal.key,
                    code = checkNotNull(body.code),
                    name = checkNotNull(body.name),
                    description = body.description?.ifEmpty { null },
                    ip = client.ip,
                    userAgent = client.userAgent,
                ),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(AudienceResponse.of(created))
    }

    companion object {
        const val ROLES_PATH = "/admin/roles"
        const val ROLE_PATH = "/admin/roles/{roleId}"
        const val AUDIENCES_PATH = "/admin/audiences"

        /** GOV-14 `auth:owner`, `auth:admin` */
        const val OWNER_OR_ADMIN = "hasAnyRole('owner', 'admin')"

        /** GOV-14 `auth:owner` */
        const val OWNER = "hasRole('owner')"

        /** DOM-03 audience·role code 형식 */
        const val CODE_PATTERN = "^[a-z][a-z0-9_]*$"

        /** 공백만 있는 값을 거부합니다. */
        const val NOT_BLANK_PATTERN = "(?s).*\\S.*"
    }
}

/** 페이지 없는 목록 (api/conventions.md §3). */
data class ItemsResponse<T>(
    val items: List<T>,
)

/** role 목록의 항목 (api/admin.md role 목록). 등록·수정 응답도 같은 형식입니다. */
data class RoleResponse(
    val id: Long,
    val audience: String,
    val code: String,
    val fullCode: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val grantedCount: Long,
) {
    companion object {
        fun of(definition: RoleDefinition): RoleResponse {
            val role = definition.role
            return RoleResponse(
                id = role.id,
                audience = role.code.audience,
                code = role.code.code,
                fullCode = role.code.value,
                name = role.name,
                description = role.description,
                isSystem = role.isSystem,
                grantedCount = definition.grantedCount,
            )
        }
    }
}

/** audience 목록의 항목 (api/admin.md audience 목록). 추가 응답도 같은 형식입니다. */
data class AudienceResponse(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
) {
    companion object {
        fun of(audience: Audience): AudienceResponse = AudienceResponse(audience.id, audience.code, audience.name, audience.description)
    }
}

/** role 등록 요청 (api/admin.md role 등록). 길이 제한은 컬럼 길이(data-model.md §3.8)입니다. */
data class DefineRoleRequest(
    @field:NotBlank val audience: String?,
    @field:NotBlank @field:Size(max = 50) @field:Pattern(regexp = AdminRoleController.CODE_PATTERN) val code: String?,
    @field:NotBlank @field:Size(max = 100) val name: String?,
    @field:Size(max = 500) val description: String?,
)

/**
 * role 수정 요청 (api/admin.md role 수정). 보내지 않은 값은 바꾸지 않고, `description`을 빈 문자열로 보내면 설명을 지웁니다.
 * `code`와 `audience`는 바꿀 수 없으므로(GOV-13) 보내면 `VALIDATION_FAILED`입니다.
 */
data class UpdateRoleRequest(
    @field:Size(min = 1, max = 100) @field:Pattern(regexp = AdminRoleController.NOT_BLANK_PATTERN) val name: String? = null,
    @field:Size(max = 500) val description: String? = null,
    @field:Null(message = "code는 바꿀 수 없습니다.") val code: Any? = null,
    @field:Null(message = "audience는 바꿀 수 없습니다.") val audience: Any? = null,
)

/** audience 추가 요청 (api/admin.md audience 추가). 길이 제한은 컬럼 길이(data-model.md §3.7)입니다. */
data class CreateAudienceRequest(
    @field:NotBlank @field:Size(max = 30) @field:Pattern(regexp = AdminRoleController.CODE_PATTERN) val code: String?,
    @field:NotBlank @field:Size(max = 100) val name: String?,
    @field:Size(max = 500) val description: String?,
)
