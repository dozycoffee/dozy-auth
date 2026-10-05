package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.outbound.mail.AfterCommitMailSender
import com.dozycoffee.auth.server.adapter.outbound.mail.ConsoleMailAdapter
import com.dozycoffee.auth.server.adapter.outbound.mail.MailSenderProperties
import com.dozycoffee.auth.server.adapter.outbound.mail.MailSenderType
import com.dozycoffee.auth.server.adapter.outbound.mail.SmtpMailAdapter
import com.dozycoffee.auth.server.adapter.outbound.metrics.MetricsMicrometerAdapter
import com.dozycoffee.auth.server.support.MailFixtures.APP_URL
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mock.env.MockEnvironment
import java.net.URI
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 메일 발송 설정 검사 (configuration.md §2, §7). */
class MailConfigTest {
    @Test
    fun `prod 프로필에서 콘솔 발송이면 기동 실패`() {
        assertFailsWith<IllegalStateException> {
            MailConfig().delivery(properties(MailSenderType.CONSOLE), null, environment("prod"))
        }
    }

    @Test
    fun `SMTP 발송인데 SMTP 서버 주소가 없으면 기동 실패`() {
        val blankHost = environment("dev").withProperty("spring.mail.host", "")

        assertFailsWith<IllegalStateException> {
            MailConfig().delivery(properties(MailSenderType.SMTP), JavaMailSenderImpl(), blankHost)
        }
        assertFailsWith<IllegalStateException> {
            MailConfig().delivery(properties(MailSenderType.SMTP), null, environment("dev"))
        }
    }

    @Test
    fun `SMTP 서버 주소가 있으면 SMTP로 보냄`() {
        val env = environment("prod").withProperty("spring.mail.host", "smtp.dozycoffee.test")

        assertIs<SmtpMailAdapter>(MailConfig().delivery(properties(MailSenderType.SMTP), JavaMailSenderImpl(), env))
    }

    @Test
    fun `SEC-03 콘솔 발송은 local 프로필에서만 링크의 토큰을 출력함`() {
        val local = MailConfig().delivery(properties(MailSenderType.CONSOLE), null, environment("local"))
        val dev = MailConfig().delivery(properties(MailSenderType.CONSOLE), null, environment("dev"))
        val test = MailConfig().delivery(properties(MailSenderType.CONSOLE), null, environment("test"))

        assertTrue(assertIs<ConsoleMailAdapter>(local).revealToken)
        assertFalse(assertIs<ConsoleMailAdapter>(dev).revealToken)
        assertFalse(assertIs<ConsoleMailAdapter>(test).revealToken)
    }

    @Test
    fun `메일 포트는 커밋 후 발송으로 감쌈`() {
        val port =
            MailConfig().sendMailPort(
                properties(MailSenderType.CONSOLE),
                emptyProvider(),
                environment("local"),
                MetricsMicrometerAdapter(SimpleMeterRegistry()),
            )

        assertIs<ConsoleMailAdapter>(assertIs<AfterCommitMailSender>(port).delegate)
        (port as AutoCloseable).close()
    }

    @Test
    fun `보내는 주소가 이메일 형식이 아니면 기동 실패`() {
        assertFailsWith<IllegalArgumentException> {
            MailSenderProperties(MailSenderType.SMTP, "no-reply", APP_URL)
        }
    }

    @Test
    fun `앱 주소가 http(s) 주소가 아니거나 쿼리가 있으면 기동 실패`() {
        val partner = APP_URL.partner

        assertFailsWith<IllegalArgumentException> { MailSenderProperties.AppUrl(URI.create("admin.dozycoffee.test"), partner) }
        assertFailsWith<IllegalArgumentException> { MailSenderProperties.AppUrl(URI.create("ftp://admin.dozycoffee.test"), partner) }
        assertFailsWith<IllegalArgumentException> {
            MailSenderProperties.AppUrl(URI.create("https://admin.dozycoffee.test?x=1"), partner)
        }
    }

    private fun properties(sender: MailSenderType) = MailSenderProperties(sender, "no-reply@dozycoffee.test", APP_URL)

    private fun environment(profile: String) = MockEnvironment().apply { setActiveProfiles(profile) }

    private fun emptyProvider() = StaticListableBeanFactory().getBeanProvider(JavaMailSender::class.java)
}
