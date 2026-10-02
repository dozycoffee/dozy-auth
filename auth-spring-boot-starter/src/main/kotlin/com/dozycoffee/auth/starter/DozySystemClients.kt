package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.Realm
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import java.time.Duration

/**
 * system token 클라이언트의 Spring MVC·WebFlux 공통 부분 (starter.md §6).
 *
 * OAuth2 Client 객체(등록, 저장소, 매니저)는 빈으로 등록하지 않고 클라이언트 빈 안에만 둡니다. 서비스가 자신의 OAuth2 Client 설정
 * (`ClientRegistrationRepository`, `OAuth2AuthorizedClientManager` 등)을 가지고 있어도 그 빈을 대체하거나, 타입으로 주입받는 곳을
 * 모호하게 만들지 않기 위해서입니다.
 */
internal object DozySystemClients {
    /** 스타터 전용 `ClientRegistration`의 id. 서비스 저장소에는 등록하지 않습니다. */
    const val REGISTRATION_ID: String = "dozy-auth"

    /** 발급받은 토큰을 저장할 때 쓰는 principal 이름. 요청한 사용자와 무관하게 서비스 하나에 토큰 하나를 둡니다. */
    const val PRINCIPAL_NAME: String = "dozy-system-client"

    /** 만료까지 이만큼 남으면 새로 발급합니다. 보낸 토큰이 상대 서비스에 닿기 전에 만료되지 않게 합니다. */
    val REFRESH_BEFORE_EXPIRY: Duration = Duration.ofSeconds(60)

    /** 토큰 엔드포인트 연결·응답 제한 시간. JWKS 조회와 같습니다. */
    val TOKEN_TIMEOUT: Duration = Duration.ofSeconds(5)

    private val log = LoggerFactory.getLogger(DozySystemClients::class.java)

    fun registration(
        properties: DozyAuthProperties,
        clientProperties: DozyAuthClientProperties,
    ): ClientRegistration =
        ClientRegistration
            .withRegistrationId(REGISTRATION_ID)
            .clientId(clientProperties.clientId)
            .clientSecret(clientProperties.clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri(tokenUri(properties))
            .build()

    /** api/internal.md "서비스 토큰 발급": `{issuer-base-uri}/realms/internal/token`. */
    fun tokenUri(properties: DozyAuthProperties): String = "${Realm.INTERNAL.issuer(properties.issuerBaseUri)}/token"

    fun authorizeRequest(): OAuth2AuthorizeRequest =
        OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID).principal(PRINCIPAL_NAME).build()

    /**
     * 발급 실패를 [DozySystemTokenException]으로 바꾸고 warn 로그를 남깁니다.
     * 로그와 메시지에는 client id와 오류 코드만 씁니다. secret과 토큰 원문은 남기지 않습니다 (SEC-03).
     */
    fun failure(
        clientId: String,
        cause: Throwable?,
    ): DozySystemTokenException {
        val error =
            when (cause) {
                is OAuth2AuthorizationException -> cause.error.errorCode
                null -> "no_token"
                else -> cause.javaClass.simpleName
            }
        log.warn("system token을 발급받지 못해 서비스 간 호출을 보내지 않습니다: client-id={}, error={}", clientId, error)
        return DozySystemTokenException(error, "system token을 발급받지 못했습니다 (client-id=$clientId, error=$error)", cause)
    }
}
