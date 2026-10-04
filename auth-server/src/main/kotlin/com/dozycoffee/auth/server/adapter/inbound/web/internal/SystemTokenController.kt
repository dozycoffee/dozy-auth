package com.dozycoffee.auth.server.adapter.inbound.web.internal

import com.dozycoffee.auth.server.adapter.inbound.web.ClientInfo
import com.dozycoffee.auth.server.application.port.inbound.internal.IssueSystemTokenCommand
import com.dozycoffee.auth.server.application.port.inbound.internal.IssueSystemTokenUseCase
import com.dozycoffee.auth.server.domain.client.ClientId
import com.dozycoffee.auth.server.domain.client.InvalidClientException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URLDecoder
import java.util.Base64

/**
 * 서비스 토큰 발급 (api/internal.md 서비스 토큰 발급, token.md §8). OAuth 2.0 client credentials이며 client 인증은 `client_secret_basic`만 받습니다.
 *
 * - 에러는 Problem Details가 아니라 OAuth 2.0 형식(`error`, `error_description`)입니다 (api/conventions.md §4의 예외, RFC 6749 §5.2).
 * - 요청 형식(파라미터)을 먼저 보고 client 인증을 나중에 합니다. 형식이 틀린 요청은 DB를 거치지 않습니다.
 * - Basic 헤더의 client_id와 secret은 디코드한 뒤 각각 URL 디코드합니다 (RFC 6749 §2.3.1).
 * - 성공·실패 모두 `Cache-Control: no-store`, `Pragma: no-cache`로 응답합니다 (RFC 6749 §5.1).
 * - 요청 제한 대상이 아닙니다 (api/conventions.md §8).
 * - 요청마다 info 로그를 한 줄 남깁니다: client_id, 요청 IP, 결과(`issued` 또는 OAuth 에러 이름). client_id는 CLI-01 형식일 때만
 *   그대로 쓰고, 형식이 틀리면 `invalid format`, Basic 헤더에서 읽지 못하면 `-`입니다. secret, 토큰, `Authorization` 헤더는
 *   남기지 않습니다 (SEC-03).
 */
@RestController
class SystemTokenController(
    private val issueSystemToken: IssueSystemTokenUseCase,
) {
    @PostMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
    fun token(request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION)
        val credentials = BasicCredentials.parse(authorization)
        val response = respond(request, authorization, credentials)
        log.info(
            "서비스 토큰 발급: client_id={}, ip={}, result={}",
            loggedClientId(credentials),
            ClientInfo.of(request).ip ?: "-",
            response.body?.get("error") ?: ISSUED,
        )
        return response
    }

    private fun respond(
        request: HttpServletRequest,
        authorization: String?,
        credentials: BasicCredentials?,
    ): ResponseEntity<Map<String, Any>> {
        if (OAUTH_PARAMETERS.any { (request.getParameterValues(it)?.size ?: 0) > 1 }) {
            return error(INVALID_REQUEST, "Request parameters must not be repeated")
        }
        val grantType = request.getParameter(GRANT_TYPE)
        if (grantType.isNullOrEmpty()) return error(INVALID_REQUEST, "Missing grant_type parameter")
        if (grantType != CLIENT_CREDENTIALS) return error(UNSUPPORTED_GRANT_TYPE, "Only client_credentials is supported")

        if (request.getParameter(CLIENT_SECRET) != null) {
            // client 인증은 client_secret_basic만 받습니다. 헤더와 함께 보내면 인증 방식을 둘 쓴 것이라 invalid_request (RFC 6749 §5.2)
            return if (authorization != null) {
                error(INVALID_REQUEST, "Multiple client authentication methods")
            } else {
                invalidClient()
            }
        }
        if (credentials == null) return invalidClient()

        val issued =
            try {
                issueSystemToken.issue(IssueSystemTokenCommand(credentials.clientId, credentials.clientSecret))
            } catch (ignored: InvalidClientException) {
                return invalidClient()
            }
        return noStore(ResponseEntity.ok()).body(
            mapOf(
                "access_token" to issued.accessToken,
                "token_type" to "Bearer",
                "expires_in" to issued.expiresIn.seconds,
            ),
        )
    }

    /** 로그에 남길 client_id. 요청 값을 그대로 남기지 않도록 CLI-01 형식인 값만 씁니다. */
    private fun loggedClientId(credentials: BasicCredentials?): String =
        when {
            credentials == null -> "-"
            else -> ClientId.parseOrNull(credentials.clientId)?.value ?: "invalid format"
        }

    private fun invalidClient(): ResponseEntity<Map<String, Any>> =
        error(INVALID_CLIENT, "Client authentication failed", HttpStatus.UNAUTHORIZED)

    private fun error(
        code: String,
        description: String,
        status: HttpStatus = HttpStatus.BAD_REQUEST,
    ): ResponseEntity<Map<String, Any>> {
        val response = noStore(ResponseEntity.status(status))
        if (status == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, BASIC_CHALLENGE)
        return response.contentType(MediaType.APPLICATION_JSON).body(mapOf("error" to code, "error_description" to description))
    }

    private fun noStore(builder: ResponseEntity.BodyBuilder): ResponseEntity.BodyBuilder =
        builder.cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")

    /** `Authorization: Basic base64(urlencode(clientId):urlencode(clientSecret))` (RFC 6749 §2.3.1, RFC 7617). */
    private class BasicCredentials(
        val clientId: String,
        val clientSecret: String,
    ) {
        override fun toString(): String = "BasicCredentials(clientId=$clientId, clientSecret=***)"

        companion object {
            private val HEADER = Regex("Basic +([A-Za-z0-9+/]+=*)", RegexOption.IGNORE_CASE)

            /** 형식이 틀리면(헤더 없음, 다른 방식, base64·URL 인코딩 오류, `:` 없음, 빈 값) `null`입니다. */
            fun parse(header: String?): BasicCredentials? {
                val encoded =
                    header
                        ?.trim()
                        ?.let(HEADER::matchEntire)
                        ?.groupValues
                        ?.get(1) ?: return null
                val decoded =
                    runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull() ?: return null
                val separator = decoded.indexOf(':')
                if (separator < 0) return null
                val clientId = urlDecode(decoded.substring(0, separator)) ?: return null
                val clientSecret = urlDecode(decoded.substring(separator + 1)) ?: return null
                if (clientId.isEmpty() || clientSecret.isEmpty()) return null
                return BasicCredentials(clientId, clientSecret)
            }

            private fun urlDecode(value: String): String? = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrNull()
        }
    }

    companion object {
        const val PATH = "/realms/internal/token"

        private val log = LoggerFactory.getLogger(SystemTokenController::class.java)
        private const val ISSUED = "issued"

        private const val GRANT_TYPE = "grant_type"
        private const val CLIENT_CREDENTIALS = "client_credentials"
        private const val CLIENT_SECRET = "client_secret"
        private val OAUTH_PARAMETERS = listOf(GRANT_TYPE, "client_id", CLIENT_SECRET, "scope")

        private const val INVALID_REQUEST = "invalid_request"
        private const val INVALID_CLIENT = "invalid_client"
        private const val UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type"

        /** RFC 7617의 Basic challenge. realm은 이 API의 realm입니다. */
        private const val BASIC_CHALLENGE = "Basic realm=\"internal\""
    }
}
