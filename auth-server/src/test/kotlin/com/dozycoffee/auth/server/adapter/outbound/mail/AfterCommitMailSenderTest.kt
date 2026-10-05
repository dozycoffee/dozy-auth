package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.server.adapter.outbound.metrics.MetricsMicrometerAdapter
import com.dozycoffee.auth.server.application.port.outbound.mail.Mail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerNotificationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.support.MailFixtures.EXPIRES_AT
import com.dozycoffee.auth.server.support.MailFixtures.RECIPIENT
import com.dozycoffee.auth.server.support.MailFixtures.invitationMail
import com.dozycoffee.auth.server.support.PersistenceTestConfiguration
import com.dozycoffee.auth.server.support.RecordingMailSender
import com.dozycoffee.auth.server.support.counted
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Service
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 메일은 트랜잭션 커밋 후에 보냅니다 (architecture.md §9, §9.3).
 *
 * 실제 트랜잭션 매니저(Exposed `SpringTransactionManager`)로 커밋·롤백해 봅니다. 발송 스레드는 호출 스레드에서 바로 실행하는
 * 실행기로 바꿔 결과를 기다리지 않고 확인합니다.
 */
@SpringBootTest(classes = [PersistenceTestConfiguration::class])
@Import(AfterCommitMailSenderTest.Config::class, AfterCommitMailSenderTest.MailingService::class)
@ActiveProfiles("test")
class AfterCommitMailSenderTest {
    @Autowired
    private lateinit var service: MailingService

    @Autowired
    private lateinit var recorder: RecordingMailSender

    @Autowired
    private lateinit var sendMailPort: SendMailPort

    @BeforeEach
    fun clear() {
        recorder.sent.clear()
    }

    @Test
    fun `트랜잭션이 커밋되면 메일을 보냄`() {
        service.sendAndCommit(invitationMail())

        assertEquals(listOf<Mail>(invitationMail()), recorder.sent)
    }

    @Test
    fun `트랜잭션이 롤백되면 메일을 보내지 않음`() {
        assertFailsWith<IllegalStateException> { service.sendAndFail(invitationMail()) }

        assertTrue(recorder.sent.isEmpty())
    }

    @Test
    fun `커밋 전에는 보내지 않고 커밋 뒤에 보냄`() {
        val sentBeforeCommit = service.sendAndCountBeforeCommit(invitationMail())

        assertEquals(0, sentBeforeCommit)
        assertEquals(1, recorder.sent.size)
    }

    @Test
    fun `트랜잭션 밖에서 호출하면 바로 보냄`() {
        sendMailPort.send(invitationMail())

        assertEquals(listOf<Mail>(invitationMail()), recorder.sent)
    }

    @Test
    fun `발송이 실패해도 호출한 쪽에 예외를 던지지 않고 실패를 지표로 셈`() {
        val broken =
            object : SendMailPort {
                override fun send(mail: Mail) = error("SMTP 연결 실패")
            }
        val registry = SimpleMeterRegistry()
        val failing = AfterCommitMailSender(broken, MetricsMicrometerAdapter(registry), Executor { it.run() })

        failing.send(invitationMail())

        assertEquals(1.0, registry.counted("dozy.auth.mail.failed", "kind", "employee_invitation"))
    }

    @Test
    fun `발송 대기열이 가득 차도 호출한 쪽에 예외를 던지지 않고 실패를 지표로 셈`() {
        val registry = SimpleMeterRegistry()
        val full =
            AfterCommitMailSender(
                RecordingMailSender(),
                MetricsMicrometerAdapter(registry),
                Executor { throw RejectedExecutionException() },
            )

        full.send(OwnerNotificationMail(RECIPIENT, AuditAction.ROLE_DELETED, EXPIRES_AT))

        assertEquals(1.0, registry.counted("dozy.auth.mail.failed", "kind", "owner_notification"))
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        fun recordingMailSender(): RecordingMailSender = RecordingMailSender()

        @Bean
        fun sendMailPort(recorder: RecordingMailSender): SendMailPort =
            AfterCommitMailSender(recorder, MetricsMicrometerAdapter(SimpleMeterRegistry()), Executor { it.run() })
    }

    /** 메일을 보내는 UseCase 구현을 흉내 냅니다. */
    @Service
    class MailingService(
        private val sendMailPort: SendMailPort,
        private val recorder: RecordingMailSender,
    ) {
        @Transactional
        fun sendAndCommit(mail: Mail) {
            sendMailPort.send(mail)
        }

        @Transactional
        fun sendAndFail(mail: Mail) {
            sendMailPort.send(mail)
            error("업무 실패")
        }

        @Transactional
        fun sendAndCountBeforeCommit(mail: Mail): Int {
            sendMailPort.send(mail)
            return recorder.sent.size
        }
    }
}
