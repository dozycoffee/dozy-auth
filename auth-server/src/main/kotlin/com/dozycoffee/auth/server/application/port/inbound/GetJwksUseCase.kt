package com.dozycoffee.auth.server.application.port.inbound

/** 서비스가 토큰 검증에 쓸 JWKS를 조회합니다 (token.md §7, api/internal.md). */
interface GetJwksUseCase {
    /** `{"keys": [...]}` 형식. 게시 중인 모든 키의 공개키만 담습니다. */
    fun getJwks(): Map<String, Any>
}
