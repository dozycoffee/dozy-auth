package com.dozycoffee.auth.starter.reactivesample

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.starter.CurrentPrincipal
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/** 스타터를 쓰는 WebFlux 서비스 역할의 샘플 앱 (서비스 역할). 코루틴과 Reactor 컨트롤러를 모두 둡니다. */
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

    @PreAuthorize("hasRole('item_manager')")
    @GetMapping("/items")
    suspend fun items() = "items"

    @PreAuthorize("hasRole('item_admin')")
    @GetMapping("/items/admin")
    suspend fun itemAdmin() = "item admin"

    @PreAuthorize("@dozyAuth.isType('EMPLOYEE')")
    @GetMapping("/employees-only")
    suspend fun employeesOnly() = "employees"

    @PreAuthorize("@dozyAuth.isType('PARTNER')")
    @GetMapping("/partners-only")
    suspend fun partnersOnly() = "partners"
}

@RestController
class MonoController {
    @PreAuthorize("hasRole('item_manager')")
    @GetMapping("/mono/items")
    fun items(): Mono<String> = Mono.just("items")

    @PreAuthorize("hasRole('item_admin')")
    @GetMapping("/mono/items/admin")
    fun itemAdmin(): Mono<String> = Mono.just("item admin")
}
