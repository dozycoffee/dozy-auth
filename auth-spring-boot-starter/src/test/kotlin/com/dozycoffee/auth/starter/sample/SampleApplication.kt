package com.dozycoffee.auth.starter.sample

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.starter.CurrentPrincipal
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** 스타터를 쓰는 서비스 역할의 샘플 앱 (WMS를 흉내 냄). */
@SpringBootApplication
class SampleApplication

@RestController
class SampleController {
    @GetMapping("/public/ping")
    fun ping() = "pong"

    @GetMapping("/me")
    fun me(
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ) = mapOf("sub" to principal.key.sub, "realm" to principal.realm.name, "roles" to principal.roles, "sid" to principal.sessionId)

    @PreAuthorize("hasRole('inbound_manager')")
    @GetMapping("/inbounds")
    fun inbounds() = "inbounds"

    @PreAuthorize("hasRole('stock_admin')")
    @GetMapping("/stocks/admin")
    fun stockAdmin() = "stock admin"

    @PreAuthorize("@dozyAuth.isType('EMPLOYEE')")
    @GetMapping("/employees-only")
    fun employeesOnly() = "employees"

    @PreAuthorize("@dozyAuth.isType('PARTNER')")
    @GetMapping("/partners-only")
    fun partnersOnly() = "partners"
}
