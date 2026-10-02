package com.dozycoffee.auth.server.adapter.inbound.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders

/**
 * 요청한 클라이언트 정보. 세션(`refresh_session.ip`, `user_agent`)과 감사 로그에 남깁니다.
 *
 * 클라이언트 주소는 이 한 곳에서만 정합니다. 지금은 서블릿의 `remoteAddr`를 그대로 씁니다. 프록시 뒤에서 실제 클라이언트 주소가
 * 필요하면 `X-Forwarded-For`를 여기서 직접 읽지 않고, 신뢰할 프록시를 정하는 서버 설정(`server.forward-headers-strategy`)으로
 * `remoteAddr`를 바꿉니다 (요청 제한 작업). 아무나 보낼 수 있는 헤더를 그대로 믿으면 요청 제한과 기록을 속일 수 있기 때문입니다.
 *
 * @property ip 클라이언트 주소 (`inet` 문자열)
 * @property userAgent `User-Agent` 헤더. 없으면 `null`
 */
data class ClientInfo(
    val ip: String?,
    val userAgent: String?,
) {
    companion object {
        fun of(request: HttpServletRequest): ClientInfo =
            ClientInfo(
                ip = request.remoteAddr?.takeIf { it.isNotBlank() },
                userAgent = request.getHeader(HttpHeaders.USER_AGENT)?.takeIf { it.isNotBlank() },
            )
    }
}
