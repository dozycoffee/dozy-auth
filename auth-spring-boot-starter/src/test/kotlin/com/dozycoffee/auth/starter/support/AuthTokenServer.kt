package com.dozycoffee.auth.starter.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 테스트용 서버. Auth의 서비스 토큰 발급(api/internal.md, `POST /realms/internal/token`)을 명세대로 흉내 내고,
 * 호출받는 다른 서비스 역할의 [RESOURCE_PATH]도 둡니다. 받은 요청을 기록합니다.
 *
 * 발급하는 토큰 값은 `system-token-1`, `system-token-2`처럼 발급 순서를 담습니다.
 */
class AuthTokenServer : AutoCloseable {
    /** 받은 토큰 요청. */
    val tokenRequests = CopyOnWriteArrayList<RecordedRequest>()

    /** 다른 서비스 역할의 경로가 받은 `Authorization` 헤더. */
    val resourceAuthorizations = CopyOnWriteArrayList<String?>()

    /** `true`이면 토큰 요청에 `invalid_client`(401)로 답합니다. */
    @Volatile
    var rejectClient: Boolean = false

    private val issued = AtomicInteger()

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(TOKEN_PATH) { exchange -> exchange.closing { token(it) } }
            createContext(RESOURCE_PATH) { exchange ->
                exchange.closing {
                    resourceAuthorizations += it.requestHeaders.getFirst("Authorization")
                    it.respond(200, "text/plain", "ok")
                }
            }
            start()
        }

    /** `dozy.auth.issuer-base-uri`로 쓸 주소. */
    val baseUri: String get() = "http://127.0.0.1:${server.address.port}"

    val resourceUri: String get() = "$baseUri$RESOURCE_PATH"

    override fun close() = server.stop(0)

    private fun token(exchange: HttpExchange) {
        tokenRequests +=
            RecordedRequest(
                method = exchange.requestMethod,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                contentType = exchange.requestHeaders.getFirst("Content-Type"),
                body = exchange.requestBody.readAllBytes().decodeToString(),
            )
        if (rejectClient) {
            exchange.respond(401, "application/json", """{"error":"invalid_client","error_description":"Client authentication failed"}""")
        } else {
            val token = "system-token-${issued.incrementAndGet()}"
            exchange.respond(200, "application/json", """{"access_token":"$token","token_type":"Bearer","expires_in":$EXPIRES_IN}""")
        }
    }

    private fun HttpExchange.closing(handle: (HttpExchange) -> Unit) {
        try {
            handle(this)
        } finally {
            close()
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }

    data class RecordedRequest(
        val method: String,
        val authorization: String?,
        val contentType: String?,
        val body: String,
    )

    companion object {
        /** 발급 응답의 `expires_in`(초). `policy.access-token-ttl`과 같은 값입니다. */
        const val EXPIRES_IN: Long = 600

        private const val TOKEN_PATH = "/realms/internal/token"
        private const val RESOURCE_PATH = "/resource"
    }
}
