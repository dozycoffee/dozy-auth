package com.dozycoffee.auth.server.adapter.inbound.web.ratelimit

import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.adapter.inbound.web.internal.JwksController
import com.dozycoffee.auth.server.adapter.inbound.web.internal.SystemTokenController
import com.dozycoffee.auth.server.application.port.inbound.system.CheckClientRateLimitUseCase
import com.dozycoffee.auth.server.domain.TooManyAttemptsException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * 인증 없는 API의 클라이언트 IP 단위 요청 제한 (api/conventions.md §8, `policy.rate-limit-ip`).
 *
 * - 인증 없는 경로의 보안 필터 체인(`SecurityConfig`의 1번 체인)에 CORS 필터 바로 뒤로 넣습니다. 그래서 인증 없는 API는 모두
 *   제한 대상이고(새 API를 그 체인에 넣으면 따로 등록하지 않아도 제한됨), 제외할 경로만 [EXCLUDED_PATHS]에 둡니다.
 *   컨트롤러와 비밀번호 검증보다 먼저 거부하고, 429 응답에도 CORS 헤더가 붙어 앱이 `code`와 `Retry-After`를 읽을 수 있습니다.
 * - CORS preflight(`OPTIONS`)는 세지 않습니다.
 * - 클라이언트 주소는 [ClientInfo]가 정합니다. 프록시 뒤의 주소는 서버 설정이 정합니다 (configuration.md §9).
 * - 한도를 넘으면 [TooManyAttemptsException]을 예외 처리기로 넘겨 다른 에러와 같은 형식(`429 TOO_MANY_ATTEMPTS`, `Retry-After`)으로
 *   응답합니다. 로그인의 계정 잠금과 같은 응답입니다 (LGN-02).
 *
 * 빈으로 등록하지 않습니다. 빈으로 등록하면 Spring Boot가 모든 요청에 적용되는 서블릿 필터로도 등록하기 때문입니다.
 */
class ClientRateLimitFilter(
    private val checkClientRateLimit: CheckClientRateLimitUseCase,
    private val resolver: HandlerExceptionResolver,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == HttpMethod.OPTIONS.name() || EXCLUDED.matches(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            checkClientRateLimit.check(ClientInfo.of(request).ip)
        } catch (ex: TooManyAttemptsException) {
            resolver.resolveException(request, response, null, ex)
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        /**
         * 인증 없는 경로 중 요청 제한에서 빼는 경로 (api/conventions.md §8).
         *
         * - JWKS: 서비스가 캐시가 비거나 모르는 `kid`를 만났을 때 받아 갑니다.
         * - 서비스 토큰 발급: client secret이 난수라 대입이 의미 없습니다.
         * - Actuator(상태 확인, Prometheus 수집): 로드 밸런서·오케스트레이터·수집기가 주기적으로 호출합니다.
         * - 개발용 API: `local`·`dev` 전용이고 비밀값을 확인하지 않아 대입할 것이 없습니다.
         */
        val EXCLUDED_PATHS: List<String> =
            listOf(JwksController.PATH, SystemTokenController.PATH, "/actuator/**", "/dev/**")

        private val EXCLUDED: RequestMatcher =
            OrRequestMatcher(EXCLUDED_PATHS.map { PathPatternRequestMatcher.withDefaults().matcher(it) })
    }
}
