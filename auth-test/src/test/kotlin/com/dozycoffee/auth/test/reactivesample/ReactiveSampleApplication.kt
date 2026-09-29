package com.dozycoffee.auth.test.reactivesample

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.starter.CurrentPrincipal
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** WebFlux + 코루틴 서비스 역할의 샘플 앱 (서비스 역할). */
@SpringBootApplication
class ReactiveSampleApplication

@RestController
class ItemController {
    @GetMapping("/me")
    suspend fun me(
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ) = mapOf("sub" to principal.key.sub, "realm" to principal.realm.name, "roles" to principal.roles)

    @PreAuthorize("hasRole('item_manager')")
    @GetMapping("/items")
    suspend fun items() = "items"

    @PreAuthorize("@dozyAuth.isType('EMPLOYEE')")
    @GetMapping("/employees-only")
    suspend fun employeesOnly() = "employees"
}
