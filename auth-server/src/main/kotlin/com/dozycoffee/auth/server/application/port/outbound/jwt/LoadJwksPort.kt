package com.dozycoffee.auth.server.application.port.outbound.jwt

/** 게시할 JWKS를 읽습니다 (token.md §7). */
interface LoadJwksPort {
    /** `{"keys": [...]}` 형식의 JWKS. 게시 중인 모든 키의 공개키만 담습니다. */
    fun load(): Map<String, Any>
}
