package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.outbound.mail.AfterCommitMailSender
import com.dozycoffee.auth.server.adapter.outbound.mail.ConsoleMailAdapter
import com.dozycoffee.auth.server.adapter.outbound.mail.MailRenderer
import com.dozycoffee.auth.server.adapter.outbound.mail.MailSenderProperties
import com.dozycoffee.auth.server.adapter.outbound.mail.MailSenderType
import com.dozycoffee.auth.server.adapter.outbound.mail.SmtpMailAdapter
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.metrics.RecordMetricsPort
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.mail.javamail.JavaMailSender
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 메일 발송 조립 (configuration.md §7, architecture.md §9.3). 설정이 잘못되면 기동 시점에 실패시킵니다 (configuration.md §2).
 *
 * `SendMailPort` 빈은 [AfterCommitMailSender] 하나이고, 실제 발송 방식(SMTP·콘솔)은 그 안에 둡니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailSenderProperties::class)
class MailConfig {
    @Bean
    fun sendMailPort(
        properties: MailSenderProperties,
        javaMailSender: ObjectProvider<JavaMailSender>,
        environment: Environment,
        metrics: RecordMetricsPort,
    ): SendMailPort = AfterCommitMailSender(delivery(properties, javaMailSender.ifAvailable, environment), metrics, mailExecutor())

    internal fun delivery(
        properties: MailSenderProperties,
        javaMailSender: JavaMailSender?,
        environment: Environment,
    ): SendMailPort {
        val renderer = MailRenderer(properties.appUrl)
        return when (properties.sender) {
            MailSenderType.SMTP -> {
                check(javaMailSender != null && !environment.getProperty("spring.mail.host").isNullOrBlank()) {
                    "AUTH_MAIL_SENDER=smtp에는 SMTP 서버 주소(AUTH_MAIL_SMTP_HOST, spring.mail.host)가 필요합니다"
                }
                SmtpMailAdapter(javaMailSender, renderer, properties.from)
            }

            MailSenderType.CONSOLE -> {
                check(!environment.matchesProfiles("prod")) {
                    "prod 프로필에서는 AUTH_MAIL_SENDER=console을 쓸 수 없습니다"
                }
                ConsoleMailAdapter(renderer, revealToken = environment.matchesProfiles("local"))
            }
        }
    }

    /**
     * 발송 전용 스레드. 대기열이 가득 차면 받지 않고 [AfterCommitMailSender]가 경고 로그를 남깁니다.
     * 다른 비동기 작업과 섞이지 않도록 Spring의 공용 실행기(`applicationTaskExecutor`)를 쓰지 않으며, 빈으로도 등록하지 않습니다
     * (`Executor` 빈이 있으면 공용 실행기 자동 설정이 꺼짐).
     */
    private fun mailExecutor(): ExecutorService {
        val sequence = AtomicInteger()
        return ThreadPoolExecutor(
            MAIL_THREADS,
            MAIL_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAIL_QUEUE_CAPACITY),
        ) { task -> Thread(task, "mail-sender-${sequence.incrementAndGet()}") }
    }

    private companion object {
        const val MAIL_THREADS = 2
        const val MAIL_QUEUE_CAPACITY = 500
    }
}
