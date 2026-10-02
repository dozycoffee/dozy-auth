package com.dozycoffee.auth.server.adapter.inbound.web.csrf

import com.dozycoffee.auth.server.domain.ForbiddenException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * refresh 쿠키를 쓰는 API(토큰 갱신, 로그아웃)의 CSRF 방어 (api/conventions.md §7).
 *
 * `SameSite=Strict`에 더해 `Origin` 헤더가 CORS 허용 목록([allowedOrigins])에 있는지 봅니다. 없거나 목록에 없으면
 * [ForbiddenException]을 예외 처리기로 넘겨 `403 FORBIDDEN`(Problem Details)으로 응답하고 컨트롤러로 넘기지 않습니다.
 *
 * - 쿠키 경로의 보안 필터 체인(`SecurityConfig`의 2번 체인)에 CORS 필터보다 **앞에** 넣습니다. CORS 필터는 허용하지 않은 origin의
 *   cross-origin 요청을 본문 없이 거부하므로, 뒤에 두면 같은 에러 형식(api/conventions.md §4)으로 응답할 수 없습니다.
 * - CORS preflight(`OPTIONS`)는 검사하지 않고 CORS 필터에 맡깁니다. preflight는 쿠키를 쓰지 않습니다.
 * - 비교는 문자열 그대로입니다. 브라우저는 `Origin`을 `scheme://host[:port]`로 보내고, 허용 목록도 같은 형식입니다.
 *
 * 빈으로 등록하지 않습니다. 빈으로 등록하면 Spring Boot가 모든 요청에 적용되는 서블릿 필터로도 등록하기 때문입니다.
 */
class RefreshCookieOriginFilter(
    allowedOrigins: Collection<String>,
    private val resolver: HandlerExceptionResolver,
) : OncePerRequestFilter() {
    private val allowedOrigins: Set<String> = allowedOrigins.toSet()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method == HttpMethod.OPTIONS.name()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.getHeader(HttpHeaders.ORIGIN) !in allowedOrigins) {
            resolver.resolveException(request, response, null, ForbiddenException(DETAIL))
            return
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val DETAIL = "허용되지 않은 Origin입니다."
    }
}
