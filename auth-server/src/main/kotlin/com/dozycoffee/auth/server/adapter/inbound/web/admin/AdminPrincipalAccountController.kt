package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.application.port.inbound.admin.DeactivateAccountCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.DeactivateAccountUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.ReactivateAccountCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.ReactivateAccountUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.SendPasswordResetCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.SendPasswordResetUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.SuspendAccountCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.SuspendAccountUseCase
import com.dozycoffee.auth.starter.CurrentPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 계정 정지·해제·비활성화, 비밀번호 재설정 메일 발송 (api/admin.md §3). 직원, 파트너, system client 모두에 씁니다.
 *
 * 필요 role은 토큰의 role 이름으로 검사합니다 (GOV-14). owner·admin 보호 규칙(GOV-02, GOV-03)은 UseCase가 DB의 현재 role로 검사합니다.
 */
@RestController
@PreAuthorize(AdminEmployeeController.OWNER_OR_ADMIN)
class AdminPrincipalAccountController(
    private val suspendAccount: SuspendAccountUseCase,
    private val reactivateAccount: ReactivateAccountUseCase,
    private val deactivateAccount: DeactivateAccountUseCase,
    private val sendPasswordReset: SendPasswordResetUseCase,
) {
    @PostMapping(SUSPEND_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun suspend(
        @PathVariable principalId: UUID,
        @Valid @RequestBody body: AccountStatusReasonRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        suspendAccount.suspend(SuspendAccountCommand(principal.key.id, principalId, body.requiredReason(), client.ip, client.userAgent))
        return ResponseEntity.noContent().build()
    }

    @PostMapping(REACTIVATE_PATH)
    fun reactivate(
        @PathVariable principalId: UUID,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        reactivateAccount.reactivate(ReactivateAccountCommand(principal.key.id, principalId, client.ip, client.userAgent))
        return ResponseEntity.noContent().build()
    }

    @PostMapping(DEACTIVATE_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun deactivate(
        @PathVariable principalId: UUID,
        @Valid @RequestBody body: AccountStatusReasonRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        deactivateAccount.deactivate(
            DeactivateAccountCommand(principal.key.id, principalId, body.requiredReason(), client.ip, client.userAgent),
        )
        return ResponseEntity.noContent().build()
    }

    @PostMapping(PASSWORD_RESET_PATH)
    fun sendPasswordReset(
        @PathVariable principalId: UUID,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        sendPasswordReset.sendPasswordReset(SendPasswordResetCommand(principal.key.id, principalId, client.ip, client.userAgent))
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    companion object {
        const val PRINCIPAL_PATH = "/admin/principals/{principalId}"
        const val SUSPEND_PATH = "$PRINCIPAL_PATH/suspend"
        const val REACTIVATE_PATH = "$PRINCIPAL_PATH/reactivate"
        const val DEACTIVATE_PATH = "$PRINCIPAL_PATH/deactivate"
        const val PASSWORD_RESET_PATH = "$PRINCIPAL_PATH/password-reset"
    }
}

/**
 * 계정 정지·비활성화 요청 (api/admin.md 계정 정지, 계정 비활성화). `reason`은 감사 로그에 남기며 비어 있을 수 없습니다.
 */
data class AccountStatusReasonRequest(
    @field:NotNull
    @field:Size(max = REASON_MAX_LENGTH)
    @field:Pattern(regexp = "(?s).*\\S.*", message = "사유가 비어 있습니다")
    val reason: String?,
) {
    fun requiredReason(): String = checkNotNull(reason)

    companion object {
        /** 사유 길이 상한 (api/admin.md 계정 정지). */
        const val REASON_MAX_LENGTH = 500
    }
}
