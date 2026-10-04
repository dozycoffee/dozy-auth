package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.inbound.web.dev.DevTokenController
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAccessDeniedHandler
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAuthenticationEntryPoint
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * 개발용 API(`/dev/...`, api/dev.md)의 보안 설정. 컨트롤러와 같이 `local`·`dev` 프로필에서만 등록합니다 (configuration.md §4).
 *
 * 다른 프로필에서는 이 체인이 없어 `/dev/...`가 [SecurityConfig]의 마지막 체인(모두 거부)에 걸립니다.
 * `prod`와 함께 켜면 기동에 실패합니다 (configuration.md §2).
 */
@Configuration(proxyBeanMethods = false)
@Profile("local", "dev")
class DevApiConfig(
    environment: Environment,
) {
    init {
        check(!environment.matchesProfiles("prod")) {
            "prod 프로필에서는 개발용 API(local·dev 프로필)를 켤 수 없습니다"
        }
    }

    /**
     * 인증 없이 엽니다. 비밀값을 확인하지 않는 API라 IP 단위 요청 제한도 걸지 않습니다 (api/conventions.md §8).
     * 다른 체인과 경로가 겹치지 않으므로 순서는 [SecurityConfig]의 마지막 체인보다 앞이기만 하면 됩니다.
     */
    @Bean
    @Order(0)
    fun devSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            securityMatcher(DevTokenController.PATHS)
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            cors { }
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
