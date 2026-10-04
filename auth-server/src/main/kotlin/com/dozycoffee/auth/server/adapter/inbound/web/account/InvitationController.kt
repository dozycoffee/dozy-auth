package com.dozycoffee.auth.server.adapter.inbound.web.account

import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.application.port.inbound.auth.AcceptInvitationCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.AcceptInvitationUseCase
import com.dozycoffee.auth.server.application.port.inbound.auth.GetInvitationUseCase
import com.dozycoffee.auth.server.domain.credential.RawPassword
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 직원 초대 조회·수락 (api/account.md). 인증 없이 호출하며, 토큰은 경로나 쿼리가 아니라 본문으로 받습니다 (VER-05). */
@RestController
class InvitationController(
    private val getInvitation: GetInvitationUseCase,
    private val acceptInvitation: AcceptInvitationUseCase,
) {
    /** 초대 조회. 토큰을 소비하지 않습니다 (VER-06). 마스킹했어도 개인정보라 캐시하지 않습니다. */
    @PostMapping(VERIFY_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun verify(
        @Valid @RequestBody body: InvitationTokenRequest,
    ): ResponseEntity<InvitationResponse> {
        val invitation = getInvitation.getInvitation(checkNotNull(body.token))
        val response =
            InvitationResponse(
                name = invitation.name,
                maskedEmail = invitation.maskedEmail,
                maskedPhone = invitation.maskedPhone,
                expiresAt = invitation.expiresAt,
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response)
    }

    /** 초대 수락. 자동 로그인하지 않으므로 쿠키나 토큰 없이 `204`입니다. */
    @PostMapping(ACCEPT_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun accept(
        @Valid @RequestBody body: AcceptInvitationRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val client = ClientInfo.of(request)
        acceptInvitation.accept(
            AcceptInvitationCommand(
                token = checkNotNull(body.token),
                password = RawPassword(checkNotNull(body.password)),
                ip = client.ip,
                userAgent = client.userAgent,
            ),
        )
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val VERIFY_PATH = "/realms/internal/invitations/verify"
        const val ACCEPT_PATH = "/realms/internal/invitations/accept"
    }
}

/** 초대 조회 요청. 토큰은 로그에 남기지 않도록 [toString]에서 가립니다 (SEC-03). */
data class InvitationTokenRequest(
    @field:NotBlank val token: String?,
) {
    override fun toString(): String = "InvitationTokenRequest(token=***)"
}

/** 초대 수락 요청. 토큰과 비밀번호는 [toString]에서 가립니다 (SEC-03). */
data class AcceptInvitationRequest(
    @field:NotBlank val token: String?,
    @field:NotBlank val password: String?,
) {
    override fun toString(): String = "AcceptInvitationRequest(token=***, password=***)"
}

/** 초대 조회 응답 (api/account.md). `maskedPhone`은 전화번호가 없으면 `null`입니다. */
data class InvitationResponse(
    val name: String,
    val maskedEmail: String,
    val maskedPhone: String?,
    val expiresAt: Instant,
)
