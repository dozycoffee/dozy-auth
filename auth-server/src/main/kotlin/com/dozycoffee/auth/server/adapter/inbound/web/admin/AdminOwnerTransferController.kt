package com.dozycoffee.auth.server.adapter.inbound.web.admin

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.application.port.inbound.admin.AcceptOwnerTransferCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.AcceptOwnerTransferUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.CancelOwnerTransferCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.CancelOwnerTransferUseCase
import com.dozycoffee.auth.server.application.port.inbound.admin.RequestOwnerTransferCommand
import com.dozycoffee.auth.server.application.port.inbound.admin.RequestOwnerTransferUseCase
import com.dozycoffee.auth.starter.CurrentPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * owner 양도 요청·수락·취소 (api/admin.md §7, GOV-09).
 *
 * - 요청과 취소는 관리 체인(`aud`에 `auth` 포함)을 거치고, 필요 role `auth:owner`는 여기서 토큰으로 검사합니다. owner인지는 UseCase가
 *   DB의 현재 role로 다시 확인합니다 (GOV-14).
 * - 수락은 `aud`를 검사하지 않는 예외라(api/conventions.md §2) 보안 설정의 수락 전용 체인이 internal realm의 직원 토큰만 받습니다.
 *   필요 role이 없으며, 양도 대상 본인인지는 UseCase가 확인합니다. 토큰은 본문으로 받습니다 (VER-05).
 */
@RestController
class AdminOwnerTransferController(
    private val requestOwnerTransfer: RequestOwnerTransferUseCase,
    private val acceptOwnerTransfer: AcceptOwnerTransferUseCase,
    private val cancelOwnerTransfer: CancelOwnerTransferUseCase,
) {
    @PreAuthorize(AdminRoleController.OWNER)
    @PostMapping(TRANSFER_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun request(
        @Valid @RequestBody body: OwnerTransferRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<OwnerTransferResponse> {
        val client = ClientInfo.of(request)
        val expiresAt =
            requestOwnerTransfer.requestOwnerTransfer(
                RequestOwnerTransferCommand(principal.key.id, checkNotNull(body.targetPrincipalId), client.ip, client.userAgent),
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(OwnerTransferResponse(expiresAt))
    }

    @PostMapping(ACCEPT_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun accept(
        @Valid @RequestBody body: OwnerTransferAcceptRequest,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        acceptOwnerTransfer.acceptOwnerTransfer(
            AcceptOwnerTransferCommand(principal.key.id, checkNotNull(body.token), client.ip, client.userAgent),
        )
        return ResponseEntity.noContent().build()
    }

    @PreAuthorize(AdminRoleController.OWNER)
    @DeleteMapping(TRANSFER_PATH)
    fun cancel(
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        cancelOwnerTransfer.cancelOwnerTransfer(CancelOwnerTransferCommand(principal.key.id, client.ip, client.userAgent))
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val TRANSFER_PATH = "/admin/owner/transfer"
        const val ACCEPT_PATH = "$TRANSFER_PATH/accept"
    }
}

/** owner 양도 요청 (api/admin.md owner 양도 요청). */
data class OwnerTransferRequest(
    @field:NotNull val targetPrincipalId: UUID?,
)

/** owner 양도 요청 응답. 수락 링크의 만료 시각입니다. */
data class OwnerTransferResponse(
    val expiresAt: Instant,
)

/** owner 양도 수락 요청. 토큰은 [toString]에서 가립니다 (SEC-03). */
data class OwnerTransferAcceptRequest(
    @field:NotBlank val token: String?,
) {
    override fun toString(): String = "OwnerTransferAcceptRequest(token=***)"
}
