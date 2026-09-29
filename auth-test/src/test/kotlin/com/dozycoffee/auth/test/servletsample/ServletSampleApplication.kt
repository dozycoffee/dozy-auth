package com.dozycoffee.auth.test.servletsample

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.starter.CurrentPrincipal
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Spring MVC 서비스 역할의 샘플 앱. */
@SpringBootApplication
class ServletSampleApplication

@RestController
class ServletItemController {
    @GetMapping("/me")
    fun me(
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ) = mapOf("sub" to principal.key.sub, "realm" to principal.realm.name, "roles" to principal.roles)

    @PreAuthorize("hasRole('item_manager')")
    @GetMapping("/items")
    fun items() = "items"
}
