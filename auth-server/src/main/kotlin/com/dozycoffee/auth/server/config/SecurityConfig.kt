package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.adapter.inbound.web.account.InvitationController
import com.dozycoffee.auth.server.adapter.inbound.web.auth.SessionController
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAccessDeniedHandler
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAuthenticationEntryPoint
import com.dozycoffee.auth.server.adapter.inbound.web.error.TraceIdFilter
import com.dozycoffee.auth.server.adapter.inbound.web.internal.JwksController
import com.dozycoffee.auth.server.adapter.inbound.web.internal.SystemTokenController
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.config.annotation.web.HttpSecurityDsl
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * HTTP 보안 설정. 경로마다 인증 방식이 달라(api/conventions.md §2) 필터 체인을 경로별로 나눕니다 (ADR-0031).
 *
 * | 순서 | 경로 | 인증 |
 * |---|---|---|
 * | 1 | [PUBLIC_PATHS] (로그인, 초대 조회·수락, 서비스 토큰 발급, JWKS, 상태 확인) | 없음 (서비스 토큰 발급의 client 인증은 컨트롤러) |
 * | 2 | `/realms/...` | 사용자 access token. `aud`는 보지 않고 `iss`의 realm이 경로와 같아야 함. system token은 403 |
 * | 3 | `/admin/...`, `/internal/...` | access token(관리)·system token. `aud`에 `auth` 포함 |
 * | 4 | 그 밖의 모든 경로 | 거부 |
 *
 * - 토큰 검증은 스타터의 디코더와 권한 변환기를 씁니다 ([TokenVerificationConfig]). 스타터의 기본 필터 체인과 401·403 처리기는
 *   이 설정과 `adapter/inbound/web/error`의 처리기가 있어 만들어지지 않습니다.
 * - 401·403은 `adapter/inbound/web/error`가 컨트롤러의 에러와 같은 형식(api/conventions.md §4)으로 응답합니다.
 * - CSRF는 쿠키를 쓰는 API(토큰 갱신, 로그아웃)가 생길 때 `Origin` 검사(api/conventions.md §7)로 다룹니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties::class)
class SecurityConfig {
    /** 1. 인증 없이 여는 경로. bearer 토큰 검증을 하지 않으므로 `Authorization` 헤더가 있어도 보지 않습니다. */
    @Bean
    @Order(1)
    fun publicSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            securityMatcher(*PUBLIC_PATHS)
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            common(entryPoint, accessDeniedHandler)
        }
        return http.build()
    }

    /** 2. `/realms/{realm}/...` 본인 API. */
    @Bean
    @Order(2)
    fun userSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
        @Qualifier(TokenVerificationConfig.USER_DECODER) decoder: JwtDecoder,
        @Qualifier(DOZY_CONVERTER) converter: Converter<Jwt, AbstractAuthenticationToken>,
    ): SecurityFilterChain {
        val provider = JwtAuthenticationProvider(decoder).apply { setJwtAuthenticationConverter(converter) }
        http {
            securityMatcher("/realms/**")
            authorizeHttpRequests { authorize(anyRequest, principalTypeIn(PrincipalType.EMPLOYEE, PrincipalType.PARTNER)) }
            oauth2ResourceServer {
                authenticationManagerResolver = RealmPathAuthenticationManagerResolver(provider)
                authenticationEntryPoint = entryPoint
            }
            common(entryPoint, accessDeniedHandler)
        }
        return http.build()
    }

    /**
     * 3. 관리·내부 API. 엔드포인트별 필요 role은 각 API에서 검사합니다.
     *
     * owner 양도 수락(`POST /admin/owner/transfer/accept`)은 `aud`를 검사하지 않는 예외라(api/conventions.md §2), owner 양도 작업에서
     * 이 체인보다 앞선 체인에 연결합니다.
     */
    @Bean
    @Order(3)
    fun managementSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
        @Qualifier(TokenVerificationConfig.AUTH_AUDIENCE_DECODER) decoder: JwtDecoder,
        @Qualifier(DOZY_CONVERTER) converter: Converter<Jwt, AbstractAuthenticationToken>,
    ): SecurityFilterChain {
        http {
            securityMatcher("/admin/**", "/internal/**")
            authorizeHttpRequests {
                authorize("/admin/**", principalTypeIn(PrincipalType.EMPLOYEE))
                authorize("/internal/**", principalTypeIn(PrincipalType.SYSTEM))
            }
            oauth2ResourceServer {
                jwt {
                    jwtDecoder = decoder
                    jwtAuthenticationConverter = converter
                }
                authenticationEntryPoint = entryPoint
            }
            common(entryPoint, accessDeniedHandler)
        }
        return http.build()
    }

    /** 4. 위에 없는 경로는 모두 거부합니다. 인증 없는 요청은 401, 그 밖에는 403입니다. */
    @Bean
    @Order(4)
    fun defaultSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests { authorize(anyRequest, denyAll) }
            common(entryPoint, accessDeniedHandler)
        }
        return http.build()
    }

    /** api/conventions.md §7. 허용 origin을 명시하고 쿠키를 허용합니다. */
    @Bean
    fun corsConfigurationSource(properties: CorsProperties): CorsConfigurationSource {
        val configuration =
            CorsConfiguration().apply {
                allowedOrigins = properties.allowedOrigins
                allowCredentials = true
                allowedMethods = CORS_METHODS.map(HttpMethod::name)
                allowedHeaders = listOf(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, TraceIdFilter.HEADER)
                exposedHeaders = listOf(TraceIdFilter.HEADER, HttpHeaders.RETRY_AFTER, HttpHeaders.WWW_AUTHENTICATE)
            }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", configuration) }
    }

    /** 모든 체인에 같은 설정: CORS, stateless, 401·403 처리기, 쓰지 않는 기본 기능 끄기. */
    private fun HttpSecurityDsl.common(
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
    ) {
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

    /** 검증한 토큰의 principal type이 [types] 중 하나일 때만 허용합니다. 토큰이 없으면 401, type이 다르면 403입니다. */
    private fun principalTypeIn(vararg types: PrincipalType): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManager { authentication, _ ->
            val type = (authentication.get()?.principal as? AuthenticatedPrincipal)?.key?.type
            AuthorizationDecision(type != null && type in types)
        }

    companion object {
        /**
         * 인증 없이 여는 경로 (api/conventions.md §2 "없음"). 인증 없는 API를 추가하면 여기에 넣습니다.
         * `/realms/...`보다 먼저 매칭되므로 `/realms/{realm}/...` 아래의 공개 API도 여기에 둡니다.
         */
        val PUBLIC_PATHS: Array<String> =
            arrayOf(
                JwksController.PATH,
                "/actuator/health",
                SessionController.LOGIN_PATH,
                InvitationController.VERIFY_PATH,
                InvitationController.ACCEPT_PATH,
                // client 인증(`Authorization: Basic`)과 OAuth 에러 응답은 컨트롤러가 함 (api/internal.md)
                SystemTokenController.PATH,
            )

        /** 스타터가 등록하는 권한 변환기 빈 이름 (starter.md §3). */
        private const val DOZY_CONVERTER = "dozyJwtAuthenticationConverter"

        private val CORS_METHODS = listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
    }
}
