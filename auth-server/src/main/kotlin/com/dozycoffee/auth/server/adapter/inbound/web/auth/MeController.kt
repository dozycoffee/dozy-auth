package com.dozycoffee.auth.server.adapter.inbound.web.auth

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.adapter.inbound.web.RealmPaths
import com.dozycoffee.auth.server.application.port.inbound.auth.GetMyProfileUseCase
import com.dozycoffee.auth.starter.CurrentPrincipal
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 내 정보 조회 (api/auth.md). 토큰 검증, `iss`의 realm과 경로의 realm 비교, system token 거부는 보안 설정(`/realms/...` 체인)이
 * 먼저 합니다 (api/conventions.md §2).
 */
@RestController
class MeController(
    private val getMyProfile: GetMyProfileUseCase,
) {
    @GetMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun me(
        @PathVariable realm: String,
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ): MeResponse {
        RealmPaths.resolve(realm, ME_REALMS)
        val profile = getMyProfile.getMyProfile(principal.key)
        return MeResponse(
            principalType = profile.principal.type.claimValue,
            principalId = profile.principal.id.toString(),
            name = profile.name,
            email = profile.email,
            roles = profile.roles,
        )
    }

    companion object {
        const val PATH = "/realms/{realm}/me"

        /** 지금 내 정보를 받는 realm. 파트너는 partner realm 작업에서 추가합니다. */
        private val ME_REALMS = setOf(Realm.INTERNAL)
    }
}

/** 내 정보 응답 (api/auth.md). */
data class MeResponse(
    val principalType: String,
    val principalId: String,
    val name: String,
    val email: String,
    val roles: List<String>,
)
