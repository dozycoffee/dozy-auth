package com.dozycoffee.auth.test

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator

/**
 * 테스트용 서명 키 (starter.md §7.2). 테스트 JVM에서 처음 쓸 때 만들고 파일로 남기지 않습니다.
 *
 * 운영 키와 섞이지 않도록 kid에 `test`를 넣습니다.
 */
internal object TestSigningKeys {
    /** 테스트 컨텍스트의 스타터가 믿는 키. */
    val TRUSTED: RSAKey by lazy { generate("dozy-test") }

    /** 스타터가 믿지 않는 키. 서명 검증 실패 테스트용입니다. */
    val UNTRUSTED: RSAKey by lazy { generate("dozy-test-untrusted") }

    private fun generate(kid: String): RSAKey = RSAKeyGenerator(2048).keyID(kid).generate()
}
