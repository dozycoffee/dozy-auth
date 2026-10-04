package com.dozycoffee.auth.server.application.port.inbound.system

/**
 * 인증 없는 API의 클라이언트 IP 단위 요청 제한 (api/conventions.md §8, `policy.rate-limit-ip`).
 *
 * 요청 제한 필터(`adapter/inbound/web/ratelimit`)가 컨트롤러보다 먼저 호출합니다.
 */
interface CheckClientRateLimitUseCase {
    /**
     * [clientIp]의 요청 1회를 셉니다. 한도를 넘으면 `TooManyAttemptsException`(`429 TOO_MANY_ATTEMPTS`, `Retry-After`)입니다.
     *
     * @param clientIp 클라이언트 주소. 없으면 주소 없는 요청끼리 한 버킷으로 셉니다
     */
    fun check(clientIp: String?)
}
