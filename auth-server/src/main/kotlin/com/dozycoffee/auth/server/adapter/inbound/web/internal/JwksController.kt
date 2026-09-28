package com.dozycoffee.auth.server.adapter.inbound.web.internal

import com.dozycoffee.auth.core.Jwks
import com.dozycoffee.auth.server.application.port.inbound.GetJwksUseCase
import com.dozycoffee.auth.server.domain.AuthPolicy
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** JWKS (api/internal.md). 인증 없이 열려 있고 요청 제한 대상이 아닙니다. */
@RestController
class JwksController(
    private val getJwks: GetJwksUseCase,
) {
    @GetMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun jwks(): ResponseEntity<Map<String, Any>> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(AuthPolicy.JWKS_CACHE_MAX_AGE).cachePublic())
            .body(getJwks.getJwks())

    companion object {
        const val PATH = Jwks.PATH
    }
}
