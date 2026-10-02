package com.dozycoffee.auth.starter

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Scope
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Clock

/**
 * WebFlux 서비스용 system token 클라이언트 (starter.md §6). 동작은 Spring MVC용([DozySystemClientServletAutoConfiguration])과 같습니다.
 *
 * `dozy.auth.client.enabled=true`일 때만 켜집니다. 토큰 캐시는 이 설정 하나에 하나이며, 주입할 때마다 새로 만드는 builder가 모두 함께 씁니다.
 */
@AutoConfiguration(
    afterName = ["org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration"],
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(WebClient::class)
@ConditionalOnProperty(prefix = "dozy.auth.client", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(DozyAuthProperties::class, DozyAuthClientProperties::class)
public class DozySystemClientReactiveAutoConfiguration(
    properties: DozyAuthProperties,
    clientProperties: DozyAuthClientProperties,
    clock: ObjectProvider<Clock>,
) {
    private val filter: ExchangeFilterFunction

    init {
        clientProperties.validate()
        filter =
            DozySystemTokenExchangeFilter(
                manager(properties, clientProperties, clock.getIfUnique { Clock.systemUTC() }),
                clientProperties.clientId,
            )
    }

    /**
     * system token을 붙이는 `WebClient.Builder`. 주입할 때마다 새 builder이므로 `baseUrl` 등을 바꿔도 다른 곳에 영향이 없습니다.
     *
     * 타입만으로는 주입되지 않습니다(`defaultCandidate = false`). 서비스의 일반 `WebClient.Builder` 주입이나 Spring Boot의 기본 builder가
     * 이 빈으로 바뀌어 system token이 엉뚱한 곳으로 나가지 않게 하기 위해서입니다.
     */
    @Bean(name = [WEB_CLIENT_BEAN_NAME], defaultCandidate = false)
    @Qualifier(WEB_CLIENT_BEAN_NAME)
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = [WEB_CLIENT_BEAN_NAME])
    public fun dozySystemWebClient(builder: ObjectProvider<WebClient.Builder>): WebClient.Builder =
        // 서비스의 기본 builder(codec, 관측 등)가 있으면 복제해서 쓴다. 원본에 filter를 붙이지 않는다.
        builder
            .getIfUnique { WebClient.builder() }
            .clone()
            .filter(filter)

    public companion object {
        /** system token을 붙이는 `WebClient.Builder` 빈 이름이자 qualifier. */
        public const val WEB_CLIENT_BEAN_NAME: String = "dozySystemWebClient"

        private fun manager(
            properties: DozyAuthProperties,
            clientProperties: DozyAuthClientProperties,
            clock: Clock,
        ): ReactiveOAuth2AuthorizedClientManager {
            val registrations = InMemoryReactiveClientRegistrationRepository(DozySystemClients.registration(properties, clientProperties))
            return AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                registrations,
                InMemoryReactiveOAuth2AuthorizedClientService(registrations),
            ).apply {
                setAuthorizedClientProvider(
                    ReactiveOAuth2AuthorizedClientProviderBuilder
                        .builder()
                        .clientCredentials { it.clock(clock).clockSkew(DozySystemClients.REFRESH_BEFORE_EXPIRY) }
                        .build(),
                )
            }
        }
    }
}

/**
 * 요청마다 캐시된 system token을 붙입니다. 토큰이 없거나 만료가 가까우면 매니저가 새로 발급합니다.
 *
 * Spring Security의 `ServerOAuth2AuthorizedClientExchangeFilterFunction`은 현재 요청의 사용자를 principal로 써서 사용자마다 토큰을
 * 따로 저장하므로 쓰지 않습니다. 발급이 응답하지 않으면 [DozySystemClients.TOKEN_TIMEOUT] 뒤에 실패합니다.
 */
internal class DozySystemTokenExchangeFilter(
    private val manager: ReactiveOAuth2AuthorizedClientManager,
    private val clientId: String,
) : ExchangeFilterFunction {
    override fun filter(
        request: ClientRequest,
        next: ExchangeFunction,
    ): Mono<ClientResponse> =
        manager
            .authorize(DozySystemClients.authorizeRequest())
            .timeout(DozySystemClients.TOKEN_TIMEOUT)
            .switchIfEmpty(Mono.error { DozySystemClients.failure(clientId, null) })
            .onErrorMap({ it !is DozySystemTokenException }) { DozySystemClients.failure(clientId, it) }
            .flatMap { client ->
                next.exchange(ClientRequest.from(request).headers { it.setBearerAuth(client.accessToken.tokenValue) }.build())
            }
}
