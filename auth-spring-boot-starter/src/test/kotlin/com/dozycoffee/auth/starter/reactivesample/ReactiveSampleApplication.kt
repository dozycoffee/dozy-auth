package com.dozycoffee.auth.starter.reactivesample

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.starter.CurrentPrincipal
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.SerializationFeature

/** 스타터를 쓰는 WebFlux 서비스 역할의 샘플 앱 (서비스 역할). 코루틴과 Reactor 컨트롤러를 모두 둡니다. */
@SpringBootApplication
class ReactiveSampleApplication {
    /** 서비스의 Jackson 설정. 401·403 본문도 이 설정으로 쓰이는지 확인하려고 들여쓰기를 켭니다. */
    @Bean
    fun indentedJson() = JsonMapperBuilderCustomizer { it.enable(SerializationFeature.INDENT_OUTPUT) }
}

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

/**
 * 자기 trace 필터가 있는 서비스 역할 (보안 필터보다 먼저 응답 `X-Trace-Id`를 정함).
 * 요청에 `X-Sample-Trace-Id`가 있을 때만 그 값을 응답 `X-Trace-Id`로 붙입니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SampleTraceIdWebFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        exchange.request.headers
            .getFirst("X-Sample-Trace-Id")
            ?.let { exchange.response.headers.set("X-Trace-Id", it) }
        return chain.filter(exchange)
    }
}
