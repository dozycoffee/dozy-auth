package com.dozycoffee.auth.server.adapter.outbound.jwt

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** RSA 개인키와 PKCS#8 PEM 사이의 변환 (configuration.md §3). 키 원문은 로그에 남기지 않습니다. */
internal object SigningKeyPem {
    private const val BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val END = "-----END PRIVATE KEY-----"
    private const val PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----"

    /** PKCS#8 PEM을 읽어 개인키와 공개키를 돌려줍니다. */
    fun read(pem: String): Pair<RSAPrivateCrtKey, RSAPublicKey> {
        require(!pem.contains(PKCS1_BEGIN)) { "PKCS#1 형식입니다. PKCS#8(`BEGIN PRIVATE KEY`)로 변환해야 합니다" }
        require(pem.contains(BEGIN) && pem.contains(END)) { "PKCS#8 PEM 형식이 아닙니다" }

        val body = pem.substringAfter(BEGIN).substringBefore(END).filterNot(Char::isWhitespace)
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey =
            keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))) as? RSAPrivateCrtKey
                ?: throw IllegalArgumentException("RSA 개인키가 아닙니다")
        val publicKey = keyFactory.generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
        return privateKey to publicKey
    }

    /** 새 RSA 키를 만들어 PKCS#8 PEM으로 돌려줍니다. */
    fun generate(
        keySize: Int,
        random: SecureRandom,
    ): String {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(keySize, random) }
        return write(generator.generateKeyPair().private.encoded)
    }

    private fun write(pkcs8: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8)
        return "$BEGIN\n$body\n$END\n"
    }
}
