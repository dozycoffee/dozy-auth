package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.RoleCode
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.application.port.inbound.admin.ListSystemClientsUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.RegisterSystemClientCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.RegisterSystemClientUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.RotateClientSecretCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.RotateClientSecretUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.SystemClientSummary
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.SystemClient
import com.dozycoffee.auth.starter.CurrentPrincipal
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * system client 목록·등록, secret 재발급 (api/admin.md §6).
 *
 * 필요 role은 토큰의 role 이름으로 검사하고(GOV-14), 관리 등급과 GOV-05·06 규칙은 UseCase가 DB의 현재 role로 검사합니다.
 * secret을 담은 응답은 `Cache-Control: no-store`입니다. secret 원문은 응답 본문에만 쓰고 로그에 남기지 않습니다 (SEC-03).
 */
@RestController
@PreAuthorize(AdminEmployeeController.OWNER_OR_ADMIN)
class AdminSystemClientController(
    private val listSystemClients: ListSystemClientsUseCase,
    private val registerSystemClient: RegisterSystemClientUseCase,
    private val rotateClientSecret: RotateClientSecretUseCase,
) {
    @GetMapping(SYSTEM_CLIENTS_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(): ItemsResponse<SystemClientResponse> = ItemsResponse(listSystemClients.listSystemClients().map(SystemClientResponse::of))

    @PostMapping(SYSTEM_CLIENTS_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun register(
        @Valid @RequestBody body: RegisterSystemClientRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<RegisteredSystemClientResponse> {
        val client = ClientInfo.of(request)
        val registered =
            registerSystemClient.registerSystemClient(
                RegisterSystemClientCommand(
                    managerId = principal.key.id,
                    clientId = ClientId(checkNotNull(body.clientId)),
                    name = checkNotNull(body.name),
                    roles =
                        body.roles
                            .orEmpty()
                            .map { RoleCode.parse(checkNotNull(it)) }
                            .toSet(),
                    ip = client.ip,
                    userAgent = client.userAgent,
                ),
            )
        val response = RegisteredSystemClientResponse(registered.principalId, registered.clientId.value, registered.secret.value)
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response)
    }

    @PostMapping(SECRET_PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun rotateSecret(
        @PathVariable principalId: UUID,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<ClientSecretResponse> {
        val client = ClientInfo.of(request)
        val secret =
            rotateClientSecret.rotateClientSecret(RotateClientSecretCommand(principal.key.id, principalId, client.ip, client.userAgent))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ClientSecretResponse(secret.value))
    }

    companion object {
        const val SYSTEM_CLIENTS_PATH = "/admin/system-clients"
        const val SECRET_PATH = "$SYSTEM_CLIENTS_PATH/{principalId}/secret"
    }
}

/**
 * system client 등록 요청 (api/admin.md system client 등록). 길이 상한은 저장 컬럼과 같습니다 ([ClientId.MAX_LENGTH], [SystemClient.NAME_MAX_LENGTH]).
 *
 * - `clientId`는 CLI-01 형식이어야 합니다.
 * - `roles`는 생략하거나 빈 목록이면 role 없이 등록합니다. 각 role은 `{audience}:{code}`이며 중복은 하나로 봅니다.
 *   Kotlin은 목록 원소의 타입 주석을 Bean Validation이 읽을 수 있게 남기지 않으므로 원소 형식은 [isRolesWellFormed]로 검사합니다.
 */
data class RegisterSystemClientRequest(
    @field:NotNull
    val clientId: String?,
    @field:NotNull
    @field:Size(max = SystemClient.NAME_MAX_LENGTH)
    @field:Pattern(regexp = "(?s).*\\S.*", message = "이름이 비어 있습니다")
    val name: String?,
    val roles: List<String?>? = null,
) {
    /** CLI-01 */
    @get:JsonIgnore
    @get:AssertTrue(message = "clientId는 svc-{서비스명} 형식이어야 합니다.")
    val isClientIdWellFormed: Boolean
        get() = clientId == null || ClientId.isValid(clientId)

    @get:JsonIgnore
    @get:AssertTrue(message = "role은 audience:code 형식이어야 합니다.")
    val isRolesWellFormed: Boolean
        get() = roles.orEmpty().all { it != null && RoleCode.parseOrNull(it) != null }
}

/** system client 목록의 항목 (api/admin.md system client 목록). secret은 담지 않습니다. */
data class SystemClientResponse(
    val principalId: UUID,
    val clientId: String,
    val name: String,
    val status: String,
    val roles: List<String>,
    val secretRotatedAt: Instant,
    val createdAt: Instant,
) {
    companion object {
        fun of(summary: SystemClientSummary): SystemClientResponse =
            SystemClientResponse(
                principalId = summary.principalId,
                clientId = summary.clientId,
                name = summary.name,
                status = summary.status.name,
                roles = summary.roles,
                secretRotatedAt = summary.secretRotatedAt,
                createdAt = summary.createdAt,
            )
    }
}

/** system client 등록 응답 (api/admin.md system client 등록). [clientSecret]은 이 응답에서만 알 수 있습니다 (CLI-02). */
data class RegisteredSystemClientResponse(
    val principalId: UUID,
    val clientId: String,
    val clientSecret: String,
) {
    /** secret은 가립니다 (SEC-03). */
    override fun toString(): String = "RegisteredSystemClientResponse(principalId=$principalId, clientId=$clientId)"
}

/** secret 재발급 응답 (api/admin.md secret 재발급). [clientSecret]은 이 응답에서만 알 수 있습니다 (CLI-02). */
data class ClientSecretResponse(
    val clientSecret: String,
) {
    /** secret은 가립니다 (SEC-03). */
    override fun toString(): String = "ClientSecretResponse(***)"
}
