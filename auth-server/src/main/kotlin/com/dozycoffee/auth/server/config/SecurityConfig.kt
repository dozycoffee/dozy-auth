package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAccessDeniedHandler
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAuthenticationEntryPoint
import com.dozycoffee.auth.server.adapter.inbound.web.internal.JwksController
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * HTTP 보안 설정. 지금은 JWKS와 상태 확인만 열고 나머지는 모두 막습니다.
 *
 * 로그인·관리·내부 API는 해당 작업에서 경로별 인증 방식(api/conventions.md §2)과 함께 엽니다.
 * 401·403은 `adapter/inbound/web/error`가 컨트롤러의 에러와 같은 형식(api/conventions.md §4)으로 응답합니다.
 * CSRF는 쿠키를 쓰는 API(토큰 갱신, 로그아웃)가 생길 때 `Origin` 검사(api/conventions.md §7)로 다룹니다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                authorize(JwksController.PATH, permitAll)
                authorize("/actuator/health", permitAll)
                authorize(anyRequest, denyAll)
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
        }
        return http.build()
    }
}
