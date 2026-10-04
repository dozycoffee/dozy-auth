package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.adapter.inbound.web.PageResponse
import com.dozycoffee.auth.server.application.port.inbound.admin.CancelInvitationCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.CancelInvitationUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.EmployeeDetail
import com.dozycoffee.auth.server.application.port.inbound.admin.EmployeeSummary
import com.dozycoffee.auth.server.application.port.inbound.admin.FieldPatch
import com.dozycoffee.auth.server.application.port.inbound.admin.GetEmployeeUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.InviteEmployeeCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.InviteEmployeeUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.InvitedEmployee
import com.dozycoffee.auth.server.application.port.inbound.admin.ListEmployeesCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.ListEmployeesUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.ResendInvitationCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.ResendInvitationUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateEmployeeCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.UpdateEmployeeUseCase
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.PageRequest
import com.dozycoffee.auth.server.domain.account.AccountStatus
import com.dozycoffee.auth.server.domain.account.EmployeeProfile
import com.dozycoffee.auth.starter.CurrentPrincipal
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
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
import java.time.Instant
import java.util.UUID

/**
 * 직원 초대·목록·상세·정보 수정, 초대 재발송·취소 (api/admin.md §1).
 *
 * 토큰 검증(realm `internal`, `aud`에 `auth`)과 직원 토큰 확인은 보안 설정의 관리 체인이 먼저 하고, 필요 role(GOV-14)은 여기서
 * `auth:owner`, `auth:admin` 중 하나인지 토큰으로 검사합니다. owner·admin 보호 규칙(GOV-02, GOV-03)은 UseCase가 DB의 현재 role로
 * 검사합니다.
 *
 * 목록 검색어 `q`는 로그에 남기지 않습니다 (SEC-03). 이 컨트롤러와 UseCase는 검색어를 로그에 쓰지 않고, 요청 객체의 `toString`도
 * 검색어를 가립니다.
 */
@RestController
@PreAuthorize(AdminEmployeeController.OWNER_OR_ADMIN)
class AdminEmployeeController(
    private val inviteEmployee: InviteEmployeeUseCase,
    private val listEmployees: ListEmployeesUseCase,
    private val getEmployee: GetEmployeeUseCase,
    private val updateEmployee: UpdateEmployeeUseCase,
    private val resendInvitation: ResendInvitationUseCase,
    private val cancelInvitation: CancelInvitationUseCase,
) {
    @PostMapping(EMPLOYEES_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun invite(
        @Valid @RequestBody body: InviteEmployeeRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<InvitedEmployeeResponse> {
        val client = ClientInfo.of(request)
        val invited =
            inviteEmployee.inviteEmployee(
                InviteEmployeeCommand(
                    managerId = principal.key.id,
                    email = Email(checkNotNull(body.email)),
                    name = checkNotNull(body.name),
                    phone = body.phone,
                    address = body.address,
                    roles =
                        body.roles
                            .orEmpty()
                            .map { RoleCode.parse(checkNotNull(it)) }
                            .toSet(),
                    ip = client.ip,
                    userAgent = client.userAgent,
                ),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(InvitedEmployeeResponse.of(invited))
    }

    @GetMapping(EMPLOYEES_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @RequestParam(required = false) status: AccountStatus?,
        @RequestParam(required = false) @Pattern(regexp = ROLE_PATTERN) role: String?,
        @RequestParam(name = "q", required = false) @Size(max = QUERY_MAX_LENGTH) query: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "${PageRequest.DEFAULT_SIZE}") @Min(1) @Max(PageRequest.MAX_SIZE.toLong()) size: Int,
    ): PageResponse<EmployeeSummaryResponse> {
        val command =
            ListEmployeesCommand(
                status = status,
                role = role?.let(RoleCode::parse),
                query = query?.trim()?.takeIf { it.isNotEmpty() },
                page = PageRequest(page, size),
            )
        return PageResponse.of(listEmployees.listEmployees(command), EmployeeSummaryResponse::of)
    }

    @GetMapping(EMPLOYEE_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun detail(
        @PathVariable principalId: UUID,
    ): EmployeeDetailResponse = EmployeeDetailResponse.of(getEmployee.getEmployee(principalId))

    @PatchMapping(EMPLOYEE_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        @PathVariable principalId: UUID,
        @Valid @RequestBody body: UpdateEmployeeRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): EmployeeDetailResponse {
        val client = ClientInfo.of(request)
        val detail =
            updateEmployee.updateEmployee(
                UpdateEmployeeCommand(
                    managerId = principal.key.id,
                    principalId = principalId,
                    name = body.namePatch(),
                    phone = body.phonePatch(),
                    address = body.addressPatch(),
                    ip = client.ip,
                    userAgent = client.userAgent,
                ),
            )
        return EmployeeDetailResponse.of(detail)
    }

    @PostMapping(INVITATION_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun resendInvitation(
        @PathVariable principalId: UUID,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ): ResponseEntity<InvitationResendResponse> {
        val expiresAt = resendInvitation.resendInvitation(ResendInvitationCommand(principal.key.id, principalId))
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(InvitationResendResponse(expiresAt))
    }

    @DeleteMapping(INVITATION_PATH)
    fun cancelInvitation(
        @PathVariable principalId: UUID,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        cancelInvitation.cancelInvitation(CancelInvitationCommand(principal.key.id, principalId, client.ip, client.userAgent))
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val EMPLOYEES_PATH = "/admin/employees"
        const val EMPLOYEE_PATH = "$EMPLOYEES_PATH/{principalId}"
        const val INVITATION_PATH = "$EMPLOYEE_PATH/invitation"

        /** GOV-14 직원 관리 API의 필요 role. 토큰의 `auth` audience role이 권한 `ROLE_{code}`가 됩니다 (starter.md §3). */
        const val OWNER_OR_ADMIN = "hasAnyRole('owner', 'admin')"

        /** `{audience}:{code}` (DOM-03). */
        private const val ROLE_PATTERN = "[a-z][a-z0-9_]*:[a-z][a-z0-9_]*"

        /** 검색어 길이 상한. 이메일 최대 길이와 같습니다. */
        private const val QUERY_MAX_LENGTH = 254
    }
}

/**
 * 직원 초대 요청 (api/admin.md 직원 초대). 길이 상한은 저장 컬럼과 같습니다 ([EmployeeProfile], [Email.MAX_LENGTH]).
 *
 * - 이메일은 공백 없이 `@` 하나로 나뉜 주소여야 합니다 ([Email]). 대소문자는 입력한 그대로 저장하고 중복은 대소문자 없이 판단합니다.
 * - `roles`는 생략하거나 빈 목록이면 role 없이 초대합니다. 각 role은 `{audience}:{code}`이며 중복은 하나로 봅니다.
 *   Kotlin은 목록 원소의 타입 주석을 Bean Validation이 읽을 수 있게 남기지 않으므로 원소 형식은 [isRolesWellFormed]로 검사합니다.
 */
data class InviteEmployeeRequest(
    @field:NotNull
    @field:Size(max = Email.MAX_LENGTH)
    val email: String?,
    @field:NotNull
    @field:Size(max = EmployeeProfile.NAME_MAX_LENGTH)
    @field:Pattern(regexp = ".*\\S.*", message = "이름이 비어 있습니다")
    val name: String?,
    @field:Size(max = EmployeeProfile.PHONE_MAX_LENGTH)
    val phone: String? = null,
    @field:Size(max = EmployeeProfile.ADDRESS_MAX_LENGTH)
    val address: String? = null,
    val roles: List<String?>? = null,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "이메일 형식이 아닙니다")
    val isEmailWellFormed: Boolean
        get() = email.let { it == null || (it.length <= Email.MAX_LENGTH && runCatching { Email(it) }.isSuccess) }

    @get:JsonIgnore
    @get:AssertTrue(message = "role은 audience:code 형식이어야 합니다.")
    val isRolesWellFormed: Boolean
        get() = roles.orEmpty().all { it != null && RoleCode.parseOrNull(it) != null }

    /** 개인정보 값은 가립니다. */
    override fun toString(): String = "InviteEmployeeRequest(roles=$roles)"
}

/** 직원 초대 응답 (api/admin.md 직원 초대). */
data class InvitedEmployeeResponse(
    val principalId: UUID,
    val status: String,
    val invitationExpiresAt: Instant,
) {
    companion object {
        fun of(invited: InvitedEmployee): InvitedEmployeeResponse =
            InvitedEmployeeResponse(invited.principalId, invited.status.name, invited.invitationExpiresAt)
    }
}

/**
 * 직원 정보 수정 요청. 보낸 필드만 수정하므로 보내지 않은 필드와 `null`로 보낸 필드를 구분합니다 (setter가 받았는지 기록).
 *
 * - `name`은 보내면 비어 있을 수 없습니다. `phone`, `address`는 `null`이면 삭제입니다.
 * - 이메일은 바꿀 수 없으므로(ACC-07) `email`을 보내면 무시하지 않고 `VALIDATION_FAILED`로 거부합니다.
 * - 길이 상한은 저장 컬럼과 같습니다 ([EmployeeProfile]).
 */
class UpdateEmployeeRequest {
    @field:Size(max = EmployeeProfile.NAME_MAX_LENGTH)
    @field:Pattern(regexp = ".*\\S.*", message = "이름이 비어 있습니다")
    var name: String? = null
        set(value) {
            field = value
            nameSent = true
        }

    @field:Size(max = EmployeeProfile.PHONE_MAX_LENGTH)
    var phone: String? = null
        set(value) {
            field = value
            phoneSent = true
        }

    @field:Size(max = EmployeeProfile.ADDRESS_MAX_LENGTH)
    var address: String? = null
        set(value) {
            field = value
            addressSent = true
        }

    /** 받기만 하고 쓰지 않습니다. 보냈는지만 봅니다. */
    var email: Any? = null
        set(value) {
            field = value
            emailSent = true
        }

    @JsonIgnore
    private var nameSent = false

    @JsonIgnore
    private var phoneSent = false

    @JsonIgnore
    private var addressSent = false

    @JsonIgnore
    private var emailSent = false

    /** `name`을 보냈다면 `null`이 아니어야 합니다. */
    @get:JsonIgnore
    @get:AssertTrue(message = "이름은 null일 수 없습니다")
    val isNameNotNull: Boolean get() = !nameSent || name != null

    /** ACC-07 이메일은 수정 API로 바꿀 수 없습니다. */
    @get:JsonIgnore
    @get:AssertTrue(message = "이메일은 바꿀 수 없습니다")
    val isEmailAbsent: Boolean get() = !emailSent

    fun namePatch(): FieldPatch<String> = if (nameSent) FieldPatch.Set(checkNotNull(name)) else FieldPatch.Keep

    fun phonePatch(): FieldPatch<String?> = if (phoneSent) FieldPatch.Set(phone) else FieldPatch.Keep

    fun addressPatch(): FieldPatch<String?> = if (addressSent) FieldPatch.Set(address) else FieldPatch.Keep

    /** 개인정보 값은 가립니다. */
    override fun toString(): String = "UpdateEmployeeRequest(nameSent=$nameSent, phoneSent=$phoneSent, addressSent=$addressSent)"
}

/** 직원 목록 항목 (api/admin.md 직원 목록). */
data class EmployeeSummaryResponse(
    val principalId: UUID,
    val email: String,
    val name: String,
    val status: String,
    val roles: List<String>,
    val createdAt: Instant,
) {
    companion object {
        fun of(summary: EmployeeSummary): EmployeeSummaryResponse =
            EmployeeSummaryResponse(
                principalId = summary.principalId,
                email = summary.email,
                name = summary.name,
                status = summary.status.name,
                roles = summary.roles,
                createdAt = summary.createdAt,
            )
    }
}

/** 직원 상세 (api/admin.md 직원 상세). `invitation`은 `PENDING`일 때만 있습니다. */
data class EmployeeDetailResponse(
    val principalId: UUID,
    val email: String,
    val name: String,
    val phone: String?,
    val address: String?,
    val status: String,
    val roles: List<String>,
    val lockedUntil: Instant?,
    val invitation: InvitationInfo?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    data class InvitationInfo(
        val expiresAt: Instant,
    )

    companion object {
        fun of(detail: EmployeeDetail): EmployeeDetailResponse =
            EmployeeDetailResponse(
                principalId = detail.principalId,
                email = detail.email,
                name = detail.name,
                phone = detail.phone,
                address = detail.address,
                status = detail.status.name,
                roles = detail.roles,
                lockedUntil = detail.lockedUntil,
                invitation = detail.invitationExpiresAt?.let(::InvitationInfo),
                createdAt = detail.createdAt,
                updatedAt = detail.updatedAt,
            )
    }
}

/** 초대 재발송 응답 (api/admin.md 초대 재발송). */
data class InvitationResendResponse(
    val invitationExpiresAt: Instant,
)
