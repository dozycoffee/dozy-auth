package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.adapter.inbound.web.RealmPaths
import com.dozycoffee.auth.server.application.port.inbound.auth.ChangePasswordCommand
import com.dozycoffee.auth.server.application.port.inbound.auth.ChangePasswordUseCase
import com.dozycoffee.auth.server.domain.credential.RawPassword
import com.dozycoffee.auth.starter.CurrentPrincipal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 비밀번호 변경 (api/auth.md 비밀번호 변경). 토큰 검증, `iss`의 realm과 경로의 realm 비교, system token 거부는 보안 설정
 * (`/realms/...` 체인)이 먼저 합니다 (api/conventions.md §2).
 *
 * 현재 세션은 토큰의 `sid`로 식별합니다.
 */
@RestController
class PasswordChangeController(
    private val changePassword: ChangePasswordUseCase,
) {
    @PostMapping(PATH, consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun changePassword(
        @PathVariable realm: String,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
        @Valid @RequestBody body: ChangePasswordRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val pathRealm = RealmPaths.resolve(realm, CHANGE_REALMS)
        val client = ClientInfo.of(request)
        changePassword.changePassword(
            ChangePasswordCommand(
                principal = principal.key,
                realm = pathRealm,
                currentSessionId = principal.sessionId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                currentPassword = RawPassword(checkNotNull(body.currentPassword)),
                newPassword = RawPassword(checkNotNull(body.newPassword)),
                ip = client.ip,
                userAgent = client.userAgent,
            ),
        )
        return ResponseEntity.noContent().build()
    }

    companion object {
        const val PATH = "/realms/{realm}/password/change"

        /** 지금 비밀번호 변경을 받는 realm. 파트너는 partner realm 작업에서 추가합니다. */
        private val CHANGE_REALMS = setOf(Realm.INTERNAL)
    }
}

/**
 * 비밀번호 변경 요청 (api/auth.md). 새 비밀번호 규칙(PWD-01~PWD-03)은 서비스가 검사합니다. 공백도 비밀번호의 일부라(PWD-01)
 * `NotBlank`가 아니라 `NotEmpty`로 필수만 확인합니다.
 * 비밀번호는 로그에 남기지 않도록 [toString]에서 가립니다 (SEC-03).
 */
data class ChangePasswordRequest(
    @field:NotEmpty val currentPassword: String?,
    @field:NotEmpty val newPassword: String?,
) {
    override fun toString(): String = "ChangePasswordRequest(currentPassword=***, newPassword=***)"
}
