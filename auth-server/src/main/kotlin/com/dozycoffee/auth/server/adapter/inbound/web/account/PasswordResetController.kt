package com.dozycoffee.auth.server.adapter.inbound.web.account

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.adapter.inbound.web.RealmPaths
import com.dozycoffee.auth.server.application.port.inbound.auth.RequestPasswordResetCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.RequestPasswordResetUseCase
import com.dozycoffee.auth.server.application.port.inbound.auth.ResetPasswordCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.ResetPasswordUseCase
import com.dozycoffee.auth.server.domain.credential.RawPassword
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 비밀번호 찾기·재설정 (api/account.md). 인증 없이 호출하며 쿠키를 쓰지 않으므로 CSRF 대상이 아닙니다. 토큰은 경로나 쿼리가 아니라
 * 본문으로 받습니다 (VER-05).
 */
@RestController
class PasswordResetController(
    private val requestPasswordReset: RequestPasswordResetUseCase,
    private val resetPassword: ResetPasswordUseCase,
) {
    /** 비밀번호 찾기. 계정 존재 여부와 관계없이 같은 `202`입니다 (LGN-04). */
    @PostMapping(FORGOT_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun forgot(
        @PathVariable realm: String,
        @Valid @RequestBody body: ForgotPasswordRequest,
    ): ResponseEntity<Void> {
        val pathRealm = RealmPaths.resolve(realm, RESET_REALMS)
        requestPasswordReset.requestPasswordReset(RequestPasswordResetCommand(pathRealm, checkNotNull(body.email)))
        return ResponseEntity.accepted().build()
    }

    /** 비밀번호 재설정. 자동 로그인하지 않으므로 쿠키나 토큰 없이 `204`입니다. */
    @PostMapping(RESET_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun reset(
        @PathVariable realm: String,
        @Valid @RequestBody body: ResetPasswordRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val pathRealm = RealmPaths.resolve(realm, RESET_REALMS)
        val client = ClientInfo.of(request)
        resetPassword.resetPassword(
            ResetPasswordCommand(
                realm = pathRealm,
                token = checkNotNull(body.token),
                newPassword = RawPassword(checkNotNull(body.newPassword)),
                ip = client.ip,
                userAgent = client.userAgent,
            ),
        )
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val FORGOT_PATH = "/realms/{realm}/password/forgot"
        const val RESET_PATH = "/realms/{realm}/password/reset"

        /** 지금 받는 realm. 파트너는 partner realm 작업에서 추가합니다. */
        private val RESET_REALMS = setOf(Realm.INTERNAL)
    }
}

/** 비밀번호 찾기 요청. 이메일 형식은 서비스가 확인하며, 틀려도 같은 `202`입니다 (LGN-04). */
data class ForgotPasswordRequest(
    @field:NotBlank val email: String?,
)

/**
 * 비밀번호 재설정 요청. 새 비밀번호 규칙(PWD-01~PWD-03)은 서비스가 검사하며, 공백도 비밀번호의 일부라(PWD-01) `NotEmpty`로
 * 필수만 확인합니다. 토큰과 비밀번호는 [toString]에서 가립니다 (SEC-03).
 */
data class ResetPasswordRequest(
    @field:NotBlank val token: String?,
    @field:NotEmpty val newPassword: String?,
) {
    override fun toString(): String = "ResetPasswordRequest(token=***, newPassword=***)"
}
