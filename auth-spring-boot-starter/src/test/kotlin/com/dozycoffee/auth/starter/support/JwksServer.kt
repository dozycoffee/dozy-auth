package com.dozycoffee.auth.starter.support

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 테스트용 JWKS 서버. 게시할 키를 바꿀 수 있고, JWKS를 몇 번 받아 갔는지 셉니다.
 */
class JwksServer : AutoCloseable {
    @Volatile
    var published: List<RSAKey> = listOf(TestKeys.CURRENT)

    val fetchCount = AtomicInteger()

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(PATH) { exchange ->
                fetchCount.incrementAndGet()
                val body = JWKSet(published.map(RSAKey::toPublicJWK)).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    val jwkSetUri: String get() = "http://127.0.0.1:${server.address.port}$PATH"

    override fun close() = server.stop(0)

    private companion object {
        const val PATH = "/.well-known/jwks.json"
    }
}
