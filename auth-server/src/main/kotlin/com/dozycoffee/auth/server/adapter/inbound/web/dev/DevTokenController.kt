package com.dozycoffee.auth.server.adapter.inbound.web.dev

import com.dozycoffee.auth.server.adapter.inbound.web.auth.TokenResponse
import com.dozycoffee.auth.server.application.port.inbound.IssueDevTokenCommand
import com.dozycoffee.auth.server.application.port.inbound.IssueDevTokenUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 개발용 API (api/dev.md). `local`·`dev` 프로필에서만 등록합니다 (configuration.md §4).
 *
 * 인증 없이 열려 있고(`DevApiConfig`의 보안 설정) 요청 제한 대상이 아닙니다 (api/conventions.md §8).
 * 발급한 토큰은 로그에 남기지 않습니다 (SEC-03).
 */
@RestController
@Profile("local", "dev")
class DevTokenController(
    private val issueDevToken: IssueDevTokenUseCase,
) {
    /** 개발용 토큰 발급. refresh 쿠키는 없습니다. */
    @PostMapping(TOKENS_PATH, consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun issue(
        @Valid @RequestBody body: DevTokenRequest,
    ): ResponseEntity<TokenResponse> {
        val issued =
            issueDevToken.issue(
                IssueDevTokenCommand(
                    realm = checkNotNull(body.realm),
                    principalType = checkNotNull(body.principalType),
                    principalId = checkNotNull(body.principalId),
                    // null 원소는 빈 문자열로 넘겨 role 형식 오류(VALIDATION_FAILED)가 되게 합니다
                    roles = body.roles.orEmpty().map { it.orEmpty() },
                ),
            )
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(TokenResponse(issued.accessToken, "Bearer", issued.expiresIn.seconds))
    }

    companion object {
        /** 개발용 API 전체 경로. 보안 설정이 `local`·`dev`에서만 인증 없이 엽니다. */
        const val PATHS = "/dev/**"

        const val TOKENS_PATH = "/dev/tokens"
    }
}

/** 개발용 토큰 발급 요청 (api/dev.md). 값의 형식과 조합은 UseCase가 검사합니다. */
data class DevTokenRequest(
    @field:NotNull val realm: String?,
    @field:NotNull val principalType: String?,
    @field:NotNull val principalId: String?,
    val roles: List<String?>? = null,
)
