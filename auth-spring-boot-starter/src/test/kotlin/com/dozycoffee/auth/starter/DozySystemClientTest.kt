package com.dozycoffee.auth.starter

import com.dozycoffee.auth.starter.support.AuthTokenServer
import com.dozycoffee.auth.starter.support.MutableClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.test.context.assertj.ApplicationContextAssertProvider
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * system token 클라이언트 (starter.md §2, §6). 토큰 엔드포인트는 [AuthTokenServer]가 api/internal.md "서비스 토큰 발급"대로 흉내 냅니다.
 * Spring MVC(`RestClient`)와 WebFlux(`WebClient`)에 같은 테스트를 돌립니다.
 */
class DozySystemClientTest {
    abstract class Contract {
        protected val server = AuthTokenServer()

        /** OAuth2 Client가 만료 시각을 시스템 시각으로 계산하므로 시스템 시각에서 시작합니다 ([MutableClock]). */
        protected val clock = MutableClock(Clock.systemUTC().instant())

        /** 이 앱 종류의 컨텍스트를 [properties], [userConfigurations], 이름 붙인 [beans]와 테스트 [clock]으로 띄웁니다. */
        protected abstract fun run(
            properties: List<String>,
            userConfigurations: List<Class<*>>,
            beans: Map<String, Any>,
            test: (ApplicationContextAssertProvider<*>) -> Unit,
        )

        /** 이 앱 종류의 system token 클라이언트 빈 이름. */
        protected abstract val beanName: String

        /** 다른 앱 종류의 system token 클라이언트 빈 이름. */
        protected abstract val otherBeanName: String

        /** 스타터 클라이언트와 서비스의 일반 builder를 주입받는 서비스 코드 역할. */
        protected abstract val callers: Class<out Callers>

        /** 서비스가 스타터 클라이언트 대신 같은 이름으로 정의하는 builder. */
        protected abstract fun serviceReplacement(): Any

        /** 서비스가 가진 OAuth2 Client 설정. */
        protected abstract val serviceOAuth2Client: Class<*>

        /** 서비스의 OAuth2 Client 빈 타입 (등록 저장소, 매니저). */
        protected abstract val serviceOAuth2ClientTypes: List<Class<*>>

        protected abstract fun findServiceRegistration(
            context: ApplicationContext,
            registrationId: String,
        ): ClientRegistration?

        private fun call(
            context: ApplicationContext,
            url: String,
        ) = context.getBean(callers).callWithSystemToken(url)

        private fun callWithServiceBuilder(
            context: ApplicationContext,
            url: String,
        ) = context.getBean(callers).callWithServiceBuilder(url)

        private val required: List<String>
            get() =
                listOf(
                    "dozy.auth.audience=sample",
                    "dozy.auth.accepted-realms=internal",
                    "dozy.auth.issuer-base-uri=${server.baseUri}",
                )

        private fun runDisabled(test: (ApplicationContextAssertProvider<*>) -> Unit) = run(required, emptyList(), emptyMap(), test)

        private fun runEnabled(
            vararg properties: String,
            userConfigurations: List<Class<*>> = emptyList(),
            beans: Map<String, Any> = emptyMap(),
            test: (ApplicationContextAssertProvider<*>) -> Unit,
        ) = run(
            required +
                listOf(
                    "dozy.auth.client.enabled=true",
                    "dozy.auth.client.client-id=$CLIENT_ID",
                    "dozy.auth.client.client-secret=$CLIENT_SECRET",
                ) + properties,
            listOf(callers) + userConfigurations,
            beans,
            test,
        )

        @AfterEach
        fun stopServer() = server.close()

        @Test
        fun `설정하지 않으면 system token 클라이언트를 등록하지 않음`() {
            runDisabled { context ->
                assertFalse(context.containsBean(beanName))
                assertTrue(context.getBeansOfType(DozyAuthClientProperties::class.java).isEmpty())
            }
        }

        @Test
        fun `앱 종류에 맞는 클라이언트만 등록`() {
            runEnabled { context ->
                assertTrue(context.containsBean(beanName))
                assertFalse(context.containsBean(otherBeanName))
            }
        }

        @Test
        fun `client-id가 없으면 기동 실패`() {
            runEnabled("dozy.auth.client.client-id=") { context ->
                val failure = checkNotNull(context.startupFailure).stackTraceToString()
                assertTrue(failure.contains("dozy.auth.client.client-id"))
            }
        }

        @Test
        fun `client-secret이 없으면 기동 실패`() {
            runEnabled("dozy.auth.client.client-secret=") { context ->
                val failure = checkNotNull(context.startupFailure).stackTraceToString()
                assertTrue(failure.contains("dozy.auth.client.client-secret"))
            }
        }

        @Test
        fun `SEC-03 설정 오류로 기동에 실패해도 client secret을 남기지 않음`() {
            runEnabled("dozy.auth.client.client-id=") { context ->
                val failure = checkNotNull(context.startupFailure).stackTraceToString()
                assertFalse(failure.contains(CLIENT_SECRET))
            }
        }

        @Test
        fun `호출에 system token을 Bearer로 붙임`() {
            runEnabled { context ->
                call(context, server.resourceUri)

                assertEquals(listOf<String?>("Bearer system-token-1"), server.resourceAuthorizations)
            }
        }

        @Test
        fun `토큰은 client_secret_basic과 client credentials로 internal realm에서 발급받음`() {
            runEnabled { context ->
                call(context, server.resourceUri)

                val request = server.tokenRequests.single()
                assertEquals("POST", request.method)
                assertEquals(
                    "Basic " + Base64.getEncoder().encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray()),
                    request.authorization,
                )
                assertTrue(checkNotNull(request.contentType).startsWith("application/x-www-form-urlencoded"))
                assertEquals("grant_type=client_credentials", request.body)
            }
        }

        @Test
        fun `만료가 가깝지 않으면 발급받은 토큰을 다시 씀`() {
            runEnabled { context ->
                call(context, server.resourceUri)
                clock.advance(Duration.ofSeconds(AuthTokenServer.EXPIRES_IN) - REFRESH_BEFORE_EXPIRY - Duration.ofSeconds(5))
                call(context, server.resourceUri)

                assertEquals(1, server.tokenRequests.size)
                assertEquals(listOf<String?>("Bearer system-token-1", "Bearer system-token-1"), server.resourceAuthorizations)
            }
        }

        @Test
        fun `만료가 가까우면 만료 전에 새로 발급받음`() {
            runEnabled { context ->
                call(context, server.resourceUri)
                clock.advance(Duration.ofSeconds(AuthTokenServer.EXPIRES_IN) - REFRESH_BEFORE_EXPIRY + Duration.ofSeconds(5))
                call(context, server.resourceUri)

                assertEquals(2, server.tokenRequests.size)
                assertEquals(listOf<String?>("Bearer system-token-1", "Bearer system-token-2"), server.resourceAuthorizations)
            }
        }

        @Test
        fun `주입할 때마다 새 builder라 여러 곳에서 써도 토큰은 한 번만 발급받음`() {
            runEnabled { context ->
                assertNotSame(context.getBean(beanName), context.getBean(beanName))

                call(context, server.resourceUri)
                call(context, server.resourceUri)

                assertEquals(1, server.tokenRequests.size)
            }
        }

        @Test
        fun `client 인증에 실패하면 호출을 보내지 않고 invalid_client 오류로 실패`() {
            server.rejectClient = true
            runEnabled { context ->
                val failure = assertFailsWith<DozySystemTokenException> { call(context, server.resourceUri) }

                assertEquals("invalid_client", failure.error)
                assertTrue(server.resourceAuthorizations.isEmpty())
            }
        }

        @Test
        fun `Auth에 연결하지 못하면 호출을 보내지 않고 실패`() {
            val closedPort = ServerSocket(0).use { it.localPort }
            runEnabled("dozy.auth.issuer-base-uri=http://127.0.0.1:$closedPort") { context ->
                assertFailsWith<DozySystemTokenException> { call(context, server.resourceUri) }

                assertTrue(server.resourceAuthorizations.isEmpty())
            }
        }

        @Test
        @ExtendWith(OutputCaptureExtension::class)
        fun `SEC-03 발급 실패 로그에 client secret을 남기지 않음`(output: CapturedOutput) {
            server.rejectClient = true
            runEnabled { context ->
                assertFailsWith<DozySystemTokenException> { call(context, server.resourceUri) }
            }

            assertTrue(output.all.contains("invalid_client"))
            assertFalse(output.all.contains(CLIENT_SECRET))
        }

        @Test
        fun `서비스가 타입으로 주입받는 일반 builder에는 system token을 붙이지 않음`() {
            runEnabled { context ->
                callWithServiceBuilder(context, server.resourceUri)

                assertEquals(listOf<String?>(null), server.resourceAuthorizations)
                assertTrue(server.tokenRequests.isEmpty())
            }
        }

        @Test
        fun `서비스의 OAuth2 Client 설정과 함께 써도 서로 바꾸지 않음`() {
            runEnabled(userConfigurations = listOf(serviceOAuth2Client)) { context ->
                serviceOAuth2ClientTypes.forEach { type -> context.getBean(type) } // 타입으로 주입받는 빈이 여전히 하나
                assertNull(findServiceRegistration(context, "dozy-auth"))

                call(context, server.resourceUri)

                assertEquals(listOf<String?>("Bearer system-token-1"), server.resourceAuthorizations)
            }
        }

        @Test
        fun `서비스가 같은 이름의 빈을 정의하면 스타터 빈이 빠짐`() {
            val serviceBuilder = serviceReplacement()
            runEnabled(beans = mapOf(beanName to serviceBuilder)) { context ->
                assertSame(serviceBuilder, context.getBean(beanName))
            }
        }
    }

    /** 서비스 코드에서 클라이언트를 쓰는 방법. 응답을 받을 때까지 기다리고, 실패하면 예외를 던집니다. */
    interface Callers {
        fun callWithSystemToken(url: String)

        fun callWithServiceBuilder(url: String)
    }

    @Nested
    inner class Servlet : Contract() {
        override fun run(
            properties: List<String>,
            userConfigurations: List<Class<*>>,
            beans: Map<String, Any>,
            test: (ApplicationContextAssertProvider<*>) -> Unit,
        ) {
            var runner =
                WebApplicationContextRunner()
                    .withConfiguration(AUTO_CONFIGURATIONS)
                    .withPropertyValues(*properties.toTypedArray())
                    .withBean(Clock::class.java, { clock })
                    .withUserConfiguration(*userConfigurations.toTypedArray())
            beans.forEach { (name, bean) -> runner = runner.withBean(name, bean.javaClass, { bean }) }
            runner.run { test(it) }
        }

        override val beanName = "dozySystemRestClient"
        override val otherBeanName = "dozySystemWebClient"
        override val callers = ServletCallers::class.java

        override fun serviceReplacement(): Any = RestClient.builder()

        override val serviceOAuth2Client = ServletServiceOAuth2Client::class.java
        override val serviceOAuth2ClientTypes = listOf(ClientRegistrationRepository::class.java, OAuth2AuthorizedClientManager::class.java)

        override fun findServiceRegistration(
            context: ApplicationContext,
            registrationId: String,
        ): ClientRegistration? = context.getBean(ClientRegistrationRepository::class.java).findByRegistrationId(registrationId)
    }

    @Nested
    inner class Reactive : Contract() {
        override fun run(
            properties: List<String>,
            userConfigurations: List<Class<*>>,
            beans: Map<String, Any>,
            test: (ApplicationContextAssertProvider<*>) -> Unit,
        ) {
            var runner =
                ReactiveWebApplicationContextRunner()
                    .withConfiguration(AUTO_CONFIGURATIONS)
                    .withPropertyValues(*properties.toTypedArray())
                    .withBean(Clock::class.java, { clock })
                    .withUserConfiguration(*userConfigurations.toTypedArray())
            beans.forEach { (name, bean) -> runner = runner.withBean(name, bean.javaClass, { bean }) }
            runner.run { test(it) }
        }

        override val beanName = "dozySystemWebClient"
        override val otherBeanName = "dozySystemRestClient"
        override val callers = ReactiveCallers::class.java

        override fun serviceReplacement(): Any = WebClient.builder()

        override val serviceOAuth2Client = ReactiveServiceOAuth2Client::class.java
        override val serviceOAuth2ClientTypes =
            listOf(ReactiveClientRegistrationRepository::class.java, ReactiveOAuth2AuthorizedClientManager::class.java)

        override fun findServiceRegistration(
            context: ApplicationContext,
            registrationId: String,
        ): ClientRegistration? =
            context.getBean(ReactiveClientRegistrationRepository::class.java).findByRegistrationId(registrationId).block()
    }

    @Component
    class ServletCallers(
        @Qualifier("dozySystemRestClient") private val systemClient: RestClient.Builder,
        private val serviceClient: RestClient.Builder,
    ) : Callers {
        override fun callWithSystemToken(url: String) {
            systemClient
                .build()
                .get()
                .uri(url)
                .retrieve()
                .toBodilessEntity()
        }

        override fun callWithServiceBuilder(url: String) {
            serviceClient
                .build()
                .get()
                .uri(url)
                .retrieve()
                .toBodilessEntity()
        }
    }

    @Component
    class ReactiveCallers(
        @Qualifier("dozySystemWebClient") private val systemClient: WebClient.Builder,
        private val serviceClient: WebClient.Builder,
    ) : Callers {
        override fun callWithSystemToken(url: String) {
            systemClient
                .build()
                .get()
                .uri(url)
                .retrieve()
                .toBodilessEntity()
                .block()
        }

        override fun callWithServiceBuilder(url: String) {
            serviceClient
                .build()
                .get()
                .uri(url)
                .retrieve()
                .toBodilessEntity()
                .block()
        }
    }

    /** 서비스가 자신의 다른 OAuth2 Client 연동을 위해 둔 설정 (Spring MVC). */
    @Configuration(proxyBeanMethods = false)
    class ServletServiceOAuth2Client {
        @Bean
        fun clientRegistrationRepository(): ClientRegistrationRepository = InMemoryClientRegistrationRepository(SERVICE_REGISTRATION)

        @Bean
        fun authorizedClientManager(registrations: ClientRegistrationRepository): OAuth2AuthorizedClientManager =
            AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, InMemoryOAuth2AuthorizedClientService(registrations))
    }

    /** 서비스가 자신의 다른 OAuth2 Client 연동을 위해 둔 설정 (WebFlux). */
    @Configuration(proxyBeanMethods = false)
    class ReactiveServiceOAuth2Client {
        @Bean
        fun reactiveClientRegistrationRepository(): ReactiveClientRegistrationRepository =
            InMemoryReactiveClientRegistrationRepository(SERVICE_REGISTRATION)

        @Bean
        fun reactiveAuthorizedClientManager(registrations: ReactiveClientRegistrationRepository): ReactiveOAuth2AuthorizedClientManager =
            AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                registrations,
                InMemoryReactiveOAuth2AuthorizedClientService(registrations),
            )
    }

    companion object {
        const val CLIENT_ID = "svc-catalog"
        const val CLIENT_SECRET = "test-client-secret-value"
        val REFRESH_BEFORE_EXPIRY: Duration = Duration.ofSeconds(60)

        /** 서비스가 다른 외부 API용으로 등록한 OAuth2 Client. */
        val SERVICE_REGISTRATION: ClientRegistration =
            ClientRegistration
                .withRegistrationId("partner-api")
                .clientId("catalog")
                .clientSecret("partner-api-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("https://partner.example.com/oauth/token")
                .build()

        val AUTO_CONFIGURATIONS: AutoConfigurations =
            AutoConfigurations.of(
                DozySystemClientServletAutoConfiguration::class.java,
                DozySystemClientReactiveAutoConfiguration::class.java,
                RestClientAutoConfiguration::class.java,
                WebClientAutoConfiguration::class.java,
            )
    }
}
