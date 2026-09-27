package com.dozycoffee.auth.server.adapter.out.jwt

import com.nimbusds.jose.jwk.RSAKey

/**
 * 읽어 들인 서명 키.
 *
 * @property active 서명에 쓰는 키 (개인키 포함)
 * @property published JWKS에 게시하는 모든 키의 공개키. [active]의 공개키도 들어 있음
 */
class SigningKeys(
    val active: RSAKey,
    val published: List<RSAKey>,
) {
    init {
        require(active.isPrivate) { "활성 키에는 개인키가 있어야 합니다" }
        require(published.none { it.isPrivate }) { "게시할 키에는 개인키가 없어야 합니다" }
        require(published.any { it.keyID == active.keyID }) { "활성 키도 게시해야 합니다" }
    }
}
