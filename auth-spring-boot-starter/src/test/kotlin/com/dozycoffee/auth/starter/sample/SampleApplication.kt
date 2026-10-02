package com.dozycoffee.auth.starter.sample

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.starter.CurrentPrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.SerializationFeature

/** 스타터를 쓰는 서비스 역할의 샘플 앱 (서비스 역할). */
@SpringBootApplication
class SampleApplication {
    /** 서비스의 Jackson 설정. 401·403 본문도 이 설정으로 쓰이는지 확인하려고 들여쓰기를 켭니다. */
    @Bean
    fun indentedJson() = JsonMapperBuilderCustomizer { it.enable(SerializationFeature.INDENT_OUTPUT) }
}

@RestController
class SampleController {
    @GetMapping("/public/ping")
    fun ping() = "pong"

    @GetMapping("/me")
    fun me(
        @CurrentPrincipal principal: AuthenticatedPrincipal,
    ) = mapOf("sub" to principal.key.sub, "realm" to principal.realm.name, "roles" to principal.roles, "sid" to principal.sessionId)

    @PreAuthorize("hasRole('item_manager')")
    @GetMapping("/items")
    fun items() = "items"

    @PreAuthorize("hasRole('item_admin')")
    @GetMapping("/items/admin")
    fun itemAdmin() = "item admin"

    @PreAuthorize("@dozyAuth.isType('EMPLOYEE')")
    @GetMapping("/employees-only")
    fun employeesOnly() = "employees"

    @PreAuthorize("@dozyAuth.isType('PARTNER')")
    @GetMapping("/partners-only")
    fun partnersOnly() = "partners"
}

/**
 * 자기 trace 필터가 있는 서비스 역할 (Auth 서버의 TraceIdFilter처럼 보안 필터보다 먼저 응답 `X-Trace-Id`를 정함).
 * 요청에 `X-Sample-Trace-Id`가 있을 때만 그 값을 응답 `X-Trace-Id`로 붙입니다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SampleTraceIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        request.getHeader("X-Sample-Trace-Id")?.let { response.setHeader("X-Trace-Id", it) }
        filterChain.doFilter(request, response)
    }
}
