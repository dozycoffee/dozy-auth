package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.server.support.MailFixtures.APP_URL
import com.dozycoffee.auth.server.support.MailFixtures.INTERNAL_APP
import com.dozycoffee.auth.server.support.MailFixtures.RECIPIENT
import com.dozycoffee.auth.server.support.MailFixtures.TOKEN
import com.dozycoffee.auth.server.support.MailFixtures.invitationMail
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * 로컬 개발 환경과 같은 Mailpit(compose.yaml)에 SMTP로 보내고, Mailpit API로 받은 메일을 확인합니다.
 */
class SmtpMailAdapterTest {
    @Test
    fun `텍스트와 HTML 본문을 함께 담아 SMTP로 보냄`() {
        val adapter = SmtpMailAdapter(mailSender(), MailRenderer(APP_URL), "no-reply@dozycoffee.test")

        adapter.send(invitationMail(name = "김직원"))

        val message = latestMessage()
        val link = "$INTERNAL_APP/invitation?token=${TOKEN.value}"
        assertEquals("[Dozy Coffee] 직원 계정 초대", message["Subject"].asString())
        assertEquals("no-reply@dozycoffee.test", message["From"]["Address"].asString())
        assertEquals("Dozy Coffee", message["From"]["Name"].asString())
        assertEquals(RECIPIENT.value, message["To"][0]["Address"].asString())
        assertContains(message["Text"].asString(), link)
        assertContains(message["Text"].asString(), "김직원님")
        assertContains(message["HTML"].asString(), "href=\"$link\"")
    }

    private fun mailSender() =
        JavaMailSenderImpl().apply {
            host = MAILPIT.host
            port = MAILPIT.getMappedPort(SMTP_PORT)
            defaultEncoding = "UTF-8"
        }

    private fun latestMessage(): JsonNode {
        val id = api("/api/v1/message/latest")["ID"].asString()
        return api("/api/v1/message/$id")
    }

    private fun api(path: String): JsonNode {
        val uri = URI.create("http://${MAILPIT.host}:${MAILPIT.getMappedPort(HTTP_PORT)}$path")
        val response = HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Mailpit API 응답 ${response.statusCode()}: $path" }
        return JSON.readTree(response.body())
    }

    companion object {
        private const val SMTP_PORT = 1025
        private const val HTTP_PORT = 8025

        private val HTTP: HttpClient = HttpClient.newHttpClient()
        private val JSON: JsonMapper = JsonMapper.builder().build()

        private val MAILPIT: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("axllent/mailpit"))
                .withExposedPorts(SMTP_PORT, HTTP_PORT)
                .waitingFor(Wait.forHttp("/livez").forPort(HTTP_PORT))

        @JvmStatic
        @BeforeAll
        fun start() = MAILPIT.start()

        @JvmStatic
        @AfterAll
        fun stop() = MAILPIT.stop()
    }
}
