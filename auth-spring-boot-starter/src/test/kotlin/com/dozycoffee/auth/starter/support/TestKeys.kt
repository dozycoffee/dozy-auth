package com.dozycoffee.auth.starter.support

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator

/** 테스트 서명 키. 만들기 비싸서 테스트 JVM 전체에서 한 번만 만듭니다. */
object TestKeys {
    val CURRENT: RSAKey by lazy { generate("dozy-2026-09") }

    /** 교체 후 새로 게시하는 키. */
    val NEXT: RSAKey by lazy { generate("dozy-2027-09") }

    /** JWKS에 없는 키. */
    val UNKNOWN: RSAKey by lazy { generate("dozy-2030-01") }

    private fun generate(kid: String): RSAKey = RSAKeyGenerator(2048).keyID(kid).generate()
}
