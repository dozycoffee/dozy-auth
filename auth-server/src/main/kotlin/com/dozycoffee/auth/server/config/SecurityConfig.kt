package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.server.adapter.inbound.web.account.InvitationController
import com.dozycoffee.auth.server.adapter.inbound.web.auth.SessionController
import com.dozycoffee.auth.server.adapter.inbound.web.csrf.RefreshCookieOriginFilter
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAccessDeniedHandler
import com.dozycoffee.auth.server.adapter.inbound.web.error.ProblemAuthenticationEntryPoint
import com.dozycoffee.auth.server.adapter.inbound.web.error.TraceIdFilter
import com.dozycoffee.auth.server.adapter.inbound.web.internal.JwksController
import com.dozycoffee.auth.server.adapter.inbound.web.internal.SystemTokenController
import com.dozycoffee.auth.server.adapter.inbound.web.ratelimit.ClientRateLimitFilter
import com.dozycoffee.auth.server.application.port.inbound.CheckClientRateLimitUseCase
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
import org.springframework.web.filter.CorsFilter
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * HTTP 보안 설정. 경로마다 인증 방식이 달라(api/conventions.md §2) 필터 체인을 경로별로 나눕니다 (ADR-0031).
 *
 * | 순서 | 경로 | 인증 |
 * |---|---|---|
 * | 1 | [PUBLIC_PATHS] (로그인, 초대 조회·수락, 서비스 토큰 발급, JWKS, 상태 확인) | 없음 (서비스 토큰 발급의 client 인증은 컨트롤러). IP 단위 요청 제한 ([ClientRateLimitFilter]) |
 * | 2 | [SessionController.REFRESH_COOKIE_PATHS] (토큰 갱신, 로그아웃) | refresh 쿠키 (서비스가 확인). `Origin` 검사 ([RefreshCookieOriginFilter]). 요청 제한 없음 |
 * | 3 | `/realms/...` | 사용자 access token. `aud`는 보지 않고 `iss`의 realm이 경로와 같아야 함. system token은 403 |
 * | 4 | `/admin/...`, `/internal/...` | access token(관리)·system token. `aud`에 `auth` 포함 |
 * | 5 | 그 밖의 모든 경로 | 거부 |
 *
 * - 토큰 검증은 스타터의 디코더와 권한 변환기를 씁니다 ([TokenVerificationConfig]). 스타터의 기본 필터 체인과 401·403 처리기는
 *   이 설정과 `adapter/inbound/web/error`의 처리기가 있어 만들어지지 않습니다.
 * - 401·403은 `adapter/inbound/web/error`가 컨트롤러의 에러와 같은 형식(api/conventions.md §4)으로 응답합니다.
 * - CSRF는 Spring Security의 토큰 방식 대신, 쿠키를 쓰는 API(2번 체인)의 `Origin` 검사(api/conventions.md §7)로 다룹니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties::class)
class SecurityConfig {
    /**
     * 1. 인증 없이 여는 경로. bearer 토큰 검증을 하지 않으므로 `Authorization` 헤더가 있어도 보지 않습니다.
     *
     * IP 단위 요청 제한(api/conventions.md §8)을 CORS 바로 뒤에 둡니다. 429 응답에도 CORS 헤더가 붙고, 컨트롤러보다 먼저 거부합니다.
     */
    @Bean
    @Order(1)
    fun publicSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
        checkClientRateLimit: CheckClientRateLimitUseCase,
        @Qualifier("handlerExceptionResolver") resolver: HandlerExceptionResolver,
    ): SecurityFilterChain {
        http {
            securityMatcher(*PUBLIC_PATHS)
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            common(entryPoint, accessDeniedHandler)
            addFilterAfter<CorsFilter>(ClientRateLimitFilter(checkClientRateLimit, resolver))
        }
        return http.build()
    }

    /**
     * 2. refresh 쿠키로 인증하는 경로 (api/conventions.md §2). bearer 토큰을 보지 않고, 쿠키는 컨트롤러 뒤의 서비스가 확인합니다.
     *
     * `Origin` 검사(api/conventions.md §7)를 CORS보다 앞에 둡니다. 인증 방식이 "없음"이 아니므로 IP 단위 요청 제한(§8)은 걸지 않습니다.
     */
    @Bean
    @Order(2)
    fun refreshCookieSecurityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDeniedHandler: ProblemAccessDeniedHandler,
        corsProperties: CorsProperties,
        @Qualifier("handlerExceptionResolver") resolver: HandlerExceptionResolver,
    ): SecurityFilterChain {
        http {
            securityMatcher(*SessionController.REFRESH_COOKIE_PATHS)
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            common(entryPoint, accessDeniedHandler)
            addFilterBefore<CorsFilter>(RefreshCookieOriginFilter(corsProperties.allowedOrigins, resolver))
        }
        return http.build()
    }

    /** 3. `/realms/{realm}/...` 본인 API. */
    @Bean
    @Order(3)
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
     * 4. 관리·내부 API. 엔드포인트별 필요 role은 각 API에서 검사합니다.
     *
     * owner 양도 수락(`POST /admin/owner/transfer/accept`)은 `aud`를 검사하지 않는 예외라(api/conventions.md §2), owner 양도 작업에서
     * 이 체인보다 앞선 체인에 연결합니다.
     */
    @Bean
    @Order(4)
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

    /** 5. 위에 없는 경로는 모두 거부합니다. 인증 없는 요청은 401, 그 밖에는 403입니다. */
    @Bean
    @Order(5)
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
         * 여기 넣은 경로는 [ClientRateLimitFilter.EXCLUDED_PATHS]에 없으면 IP 단위 요청 제한 대상입니다.
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
