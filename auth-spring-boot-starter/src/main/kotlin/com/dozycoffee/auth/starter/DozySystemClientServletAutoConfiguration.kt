package com.dozycoffee.auth.starter

import com.nimbusds.jose.util.JSONObjectUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Scope
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock

/**
 * Spring MVC 서비스용 system token 클라이언트 (starter.md §6). WebFlux는 [DozySystemClientReactiveAutoConfiguration]입니다.
 *
 * `dozy.auth.client.enabled=true`일 때만 켜집니다. 토큰 캐시는 이 설정 하나에 하나이며, 주입할 때마다 새로 만드는 builder가 모두 함께 씁니다.
 */
@AutoConfiguration(
    afterName = ["org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration"],
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "dozy.auth.client", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(DozyAuthProperties::class, DozyAuthClientProperties::class)
public class DozySystemClientServletAutoConfiguration(
    properties: DozyAuthProperties,
    clientProperties: DozyAuthClientProperties,
    clock: ObjectProvider<Clock>,
) {
    private val interceptor: ClientHttpRequestInterceptor

    init {
        clientProperties.validate()
        interceptor =
            DozySystemTokenInterceptor(
                manager(properties, clientProperties, clock.getIfUnique { Clock.systemUTC() }),
                clientProperties.clientId,
            )
    }

    /**
     * system token을 붙이는 `RestClient.Builder`. 주입할 때마다 새 builder이므로 `baseUrl` 등을 바꿔도 다른 곳에 영향이 없습니다.
     *
     * 타입만으로는 주입되지 않습니다(`defaultCandidate = false`). 서비스의 일반 `RestClient.Builder` 주입이나 Spring Boot의 기본 builder가
     * 이 빈으로 바뀌어 system token이 엉뚱한 곳으로 나가지 않게 하기 위해서입니다.
     */
    @Bean(name = [REST_CLIENT_BEAN_NAME], defaultCandidate = false)
    @Qualifier(REST_CLIENT_BEAN_NAME)
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = [REST_CLIENT_BEAN_NAME])
    public fun dozySystemRestClient(builder: ObjectProvider<RestClient.Builder>): RestClient.Builder =
        // 서비스의 기본 builder(메시지 변환기, 관측 등)가 있으면 복제해서 쓴다. 원본에 interceptor를 붙이지 않는다.
        builder
            .getIfUnique { RestClient.builder() }
            .clone()
            .requestInterceptor(interceptor)

    public companion object {
        /** system token을 붙이는 `RestClient.Builder` 빈 이름이자 qualifier. */
        public const val REST_CLIENT_BEAN_NAME: String = "dozySystemRestClient"

        private fun manager(
            properties: DozyAuthProperties,
            clientProperties: DozyAuthClientProperties,
            clock: Clock,
        ): OAuth2AuthorizedClientManager {
            val registrations = InMemoryClientRegistrationRepository(DozySystemClients.registration(properties, clientProperties))
            return AuthorizedClientServiceOAuth2AuthorizedClientManager(
                registrations,
                InMemoryOAuth2AuthorizedClientService(registrations),
            ).apply {
                setAuthorizedClientProvider(
                    OAuth2AuthorizedClientProviderBuilder
                        .builder()
                        .clientCredentials {
                            it
                                .clock(clock)
                                .clockSkew(DozySystemClients.REFRESH_BEFORE_EXPIRY)
                                .accessTokenResponseClient(tokenResponseClient())
                        }.build(),
                )
            }
        }

        /**
         * Spring Security 기본 설정에 두 가지를 더합니다.
         *
         * - 연결·응답 제한 시간. Auth가 응답하지 않을 때 호출이 끝없이 기다리지 않게 합니다.
         * - 400이 아닌 4xx(`invalid_client`는 401)의 OAuth 오류 본문 읽기. Spring Security의 Spring MVC용 오류 처리는 400만 읽어
         *   `invalid_client`가 `invalid_token_response`로 바뀌기 때문입니다 (WebFlux용은 읽음).
         */
        private fun tokenResponseClient(): RestClientClientCredentialsTokenResponseClient {
            // HttpURLConnection(SimpleClientHttpRequestFactory)은 POST의 401 응답 본문을 버리므로 JDK HttpClient를 쓴다
            val requestFactory =
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(DozySystemClients.TOKEN_TIMEOUT).build()).apply {
                    setReadTimeout(DozySystemClients.TOKEN_TIMEOUT)
                }
            val restClient =
                RestClient
                    .builder()
                    .requestFactory(requestFactory)
                    .configureMessageConverters {
                        it.addCustomConverter(FormHttpMessageConverter())
                        it.addCustomConverter(OAuth2AccessTokenResponseHttpMessageConverter())
                    }.defaultStatusHandler({ it.is4xxClientError }) { _, response -> throw oauth2Error(response) }
                    .defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
                    .build()
            return RestClientClientCredentialsTokenResponseClient().apply { setRestClient(restClient) }
        }

        /** OAuth 오류 본문(`{"error": ...}`)의 오류 코드를 읽습니다. 읽지 못하면 응답 status만 담습니다. `error_description`은 쓰지 않습니다. */
        private fun oauth2Error(response: ClientHttpResponse): OAuth2AuthorizationException {
            val error =
                runCatching { JSONObjectUtils.parse(response.body.readAllBytes().decodeToString())[OAuth2ParameterNames.ERROR] as String }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::OAuth2Error)
                    ?: OAuth2Error("http_${response.statusCode.value()}")
            return OAuth2AuthorizationException(error)
        }
    }
}

/** 요청마다 캐시된 system token을 붙입니다. 토큰이 없거나 만료가 가까우면 매니저가 새로 발급합니다. */
internal class DozySystemTokenInterceptor(
    private val manager: OAuth2AuthorizedClientManager,
    private val clientId: String,
) : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val client =
            try {
                manager.authorize(DozySystemClients.authorizeRequest())
            } catch (e: RuntimeException) {
                throw DozySystemClients.failure(clientId, e)
            } ?: throw DozySystemClients.failure(clientId, null)
        request.headers.setBearerAuth(client.accessToken.tokenValue)
        return execution.execute(request, body)
    }
}
