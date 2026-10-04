package com.dozycoffee.auth.server.application.port.inbound.auth

import com.dozycoffee.auth.core.PrincipalKey

/** 로그인한 사용자의 표시 정보를 조회합니다 (api/auth.md 내 정보). */
interface GetMyProfileUseCase {
    /**
     * @param principal 검증한 토큰의 주체 (`employee`만. 파트너는 partner realm 작업에서 추가)
     * @throws com.dozycoffee.auth.server.domain.UnauthenticatedException 계정이 없거나 비활성화됐을 때
     */
    fun getMyProfile(principal: PrincipalKey): MyProfile
}

/**
 * 내 정보. `roles`는 DB의 현재 값입니다.
 *
 * @property roles `{audience}:{code}` 목록
 */
data class MyProfile(
    val principal: PrincipalKey,
    val name: String,
    val email: String,
    val roles: List<String>,
)
