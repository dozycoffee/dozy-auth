package com.dozycoffee.auth.core

/**
 * 검증을 통과한 토큰의 주체.
 *
 * @property key 주체 식별자
 * @property realm 토큰을 발급한 realm (`iss`)
 * @property roles 받는 쪽 audience의 role만, prefix를 뗀 code (예: `inbound_manager`)
 * @property sessionId 토큰의 `sid`. system token은 `null`
 * @throws IllegalArgumentException [realm]이 [key]의 종류를 받을 수 없거나(DOM-01), [roles]에 형식(DOM-03)이 틀린 값이 있을 때
 */
public data class AuthenticatedPrincipal(
    public val key: PrincipalKey,
    public val realm: Realm,
    public val roles: Set<String>,
    public val sessionId: String?,
) {
    init {
        require(realm.allows(key.type)) { "${realm.pathValue} realm은 ${key.type.claimValue}를 받을 수 없습니다" }
        require(roles.all(RoleCode::isValidCode)) { "role code 형식이 올바르지 않습니다: $roles" }
    }
}
