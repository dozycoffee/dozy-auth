package com.dozycoffee.auth.server.adapter.inbound.web.internal

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.adapter.outbound.persistence.PrincipalTable
import com.dozycoffee.auth.server.adapter.outbound.persistence.SystemClientTable
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.SecretHash
import com.dozycoffee.auth.starter.DozySystemClientServletAutoConfiguration
import com.dozycoffee.auth.starter.DozySystemTokenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 서비스 토큰 발급 API를 스타터의 system token 클라이언트(`dozySystemRestClient`)가 실제 HTTP로 받아 쓰는지 확인합니다
 * (api/internal.md 서비스 토큰 발급, starter.md §6).
 *
 * 클라이언트는 서버 컨텍스트가 아니라 스타터 자동 설정만 올린 별도 컨텍스트([WebApplicationContextRunner])에서 만듭니다. 서비스 앱이
 * `dozy.auth.client.*`를 설정했을 때와 같은 빈이며, Auth 서버 자신은 이 클라이언트를 켜지 않기 때문입니다.
 * 서비스 간 호출은 상대 서비스로 보내지 않고, 스타터가 붙인 `Authorization` 헤더를 마지막 interceptor에서 받아 둡니다.
 *
 * 서버가 다른 스레드에서 요청을 처리하므로 데이터를 커밋하고, 테스트가 끝나면 지웁니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
class SystemTokenClientInteropTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val created = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        inTransaction {
            created.forEach { id ->
                SystemClientTable.deleteWhere { principalId eq id }
                PrincipalTable.deleteWhere { PrincipalTable.id eq id }
            }
        }
    }

    @Test
    fun `스타터 클라이언트가 system token을 받아 서비스 간 호출에 Bearer로 붙임`() {
        val secret = OpaqueSecret.generate().value
        val clientId = registerClient(secret)

        val sent = withStarterClient(clientId, secret) { client -> client.call() }

        assertTrue(sent.startsWith("Bearer "), sent)
        assertTrue(sent.removePrefix("Bearer ").isNotBlank())
    }

    @Test
    fun `URL 인코딩이 필요한 문자가 있는 secret도 스타터 클라이언트로 인증`() {
        val secret = "a+b/c:d%e f"
        val clientId = registerClient(secret)

        val sent = withStarterClient(clientId, secret) { client -> client.call() }

        assertTrue(sent.startsWith("Bearer "), sent)
    }

    @Test
    fun `secret이 틀리면 스타터 클라이언트가 invalid_client로 호출을 보내지 않음`() {
        val clientId = registerClient(OpaqueSecret.generate().value)

        val failure =
            withStarterClient(clientId, "wrong-secret") { client ->
                assertFailsWith<DozySystemTokenException> { client.call() }
            }

        assertEquals("invalid_client", failure.error)
    }

    @Test
    fun `스타터 클라이언트는 받은 토큰을 재사용해 두 번 호출해도 한 번만 발급`(output: CapturedOutput) {
        val secret = OpaqueSecret.generate().value
        val clientId = registerClient(secret)

        val (first, second) = withStarterClient(clientId, secret) { client -> client.call() to client.call() }

        assertEquals(first, second)
        val issued = output.all.lines().filter { "client_id=$clientId," in it && "result=issued" in it }
        assertEquals(1, issued.size, issued.joinToString("\n"))
        assertFalse(secret in output.all)
        assertFalse(first.removePrefix("Bearer ") in output.all)
    }

    /**
     * 스타터 자동 설정만 올린 서비스 컨텍스트에서 `dozySystemRestClient`로 만든 클라이언트를 [test]에 넘깁니다.
     * issuer 기준 주소는 실행 중인 서버이므로 토큰 엔드포인트가 `http://localhost:{port}/realms/internal/token`이 됩니다.
     */
    private fun <T> withStarterClient(
        clientId: String,
        secret: String,
        test: (CapturingClient) -> T,
    ): T {
        var result: Result<T>? = null
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DozySystemClientServletAutoConfiguration::class.java))
            .withPropertyValues(
                "dozy.auth.audience=catalog",
                "dozy.auth.accepted-realms=internal",
                "dozy.auth.issuer-base-uri=http://localhost:$port",
                "dozy.auth.client.enabled=true",
                "dozy.auth.client.client-id=$clientId",
                "dozy.auth.client.client-secret=$secret",
            ).run { context ->
                val builder =
                    context.getBean(
                        DozySystemClientServletAutoConfiguration.REST_CLIENT_BEAN_NAME,
                        RestClient.Builder::class.java,
                    )
                result = runCatching { test(CapturingClient(builder)) }
            }
        return checkNotNull(result).getOrThrow()
    }

    /** 스타터 builder에 interceptor를 하나 더 붙여, 스타터가 붙인 `Authorization` 헤더를 받아 두고 상대 서비스에는 보내지 않습니다. */
    private class CapturingClient(
        builder: RestClient.Builder,
    ) {
        private var authorization: String? = null

        private val client =
            builder
                .requestInterceptor { request, _, _ ->
                    authorization = request.headers.getFirst(HttpHeaders.AUTHORIZATION)
                    MockClientHttpResponse(ByteArray(0), HttpStatus.NO_CONTENT)
                }.build()

        /** Store 호출 역할. 보낸 `Authorization` 헤더를 돌려줍니다. */
        fun call(): String {
            authorization = null
            client
                .get()
                .uri("http://store.invalid/internal/ping")
                .retrieve()
                .toBodilessEntity()
            return checkNotNull(authorization) { "Authorization 헤더 없이 호출함" }
        }
    }

    private fun registerClient(secret: String): String {
        val clientId = "svc-interop-${UUID.randomUUID().toString().replace("-", "").take(SUFFIX_LENGTH)}"
        inTransaction {
            val principalId =
                PrincipalTable
                    .insertReturning(listOf(PrincipalTable.id)) {
                        it[type] = "SYSTEM"
                        it[status] = "ACTIVE"
                    }.single()[PrincipalTable.id]
            created += principalId
            SystemClientTable.insert {
                it[SystemClientTable.principalId] = principalId
                it[SystemClientTable.clientId] = clientId
                it[clientSecretHash] = SecretHash.of(secret).hex
                it[name] = "연동 테스트"
                it[secretRotatedAt] = AT
            }
        }
        return clientId
    }

    private fun <T> inTransaction(block: () -> T): T? = TransactionTemplate(transactionManager).execute { block() }

    private companion object {
        const val SUFFIX_LENGTH = 12
        val AT: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
