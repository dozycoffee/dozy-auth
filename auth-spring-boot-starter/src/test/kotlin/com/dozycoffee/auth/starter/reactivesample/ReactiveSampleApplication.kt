package com.dozycoffee.auth.starter.reactivesample

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.starter.CurrentPrincipal
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/** 스타터를 쓰는 WebFlux 서비스 역할의 샘플 앱 (WMS를 흉내 냄). 코루틴과 Reactor 컨트롤러를 모두 둡니다. */
@SpringBootApplication
class ReactiveSampleApplication

@RestController
class CoroutineController {
    @GetMapping("/public/ping")
    suspend fun ping() = "pong"

    @GetMapping("/me")
    suspend fun me(
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ) = mapOf("sub" to principal.key.sub, "realm" to principal.realm.name, "roles" to principal.roles, "sid" to principal.sessionId)

    @PreAuthorize("hasRole('inbound_manager')")
    @GetMapping("/inbounds")
    suspend fun inbounds() = "inbounds"

    @PreAuthorize("hasRole('stock_admin')")
    @GetMapping("/stocks/admin")
    suspend fun stockAdmin() = "stock admin"

    @PreAuthorize("@dozyAuth.isType('EMPLOYEE')")
    @GetMapping("/employees-only")
    suspend fun employeesOnly() = "employees"

    @PreAuthorize("@dozyAuth.isType('PARTNER')")
    @GetMapping("/partners-only")
    suspend fun partnersOnly() = "partners"
}

@RestController
class MonoController {
    @PreAuthorize("hasRole('inbound_manager')")
    @GetMapping("/mono/inbounds")
    fun inbounds(): Mono<String> = Mono.just("inbounds")

    @PreAuthorize("hasRole('stock_admin')")
    @GetMapping("/mono/stocks/admin")
    fun stockAdmin(): Mono<String> = Mono.just("stock admin")
}
