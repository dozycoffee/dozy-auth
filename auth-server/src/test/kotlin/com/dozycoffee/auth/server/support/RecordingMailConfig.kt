package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.server.adapter.outbound.mail.AfterCommitMailSender
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.application.port.outbound.metrics.RecordMetricsPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * 보낸 메일을 확인하는 통합 테스트용 설정. 커밋 후 발송(architecture.md §9.3)을 그대로 거치되, 보내는 대신 [RecordingMailSender]에
 * 기록합니다. 발송 스레드 대신 커밋한 스레드에서 바로 기록합니다.
 */
@TestConfiguration(proxyBeanMethods = false)
class RecordingMailConfig {
    @Bean
    fun recordingMailSender(): RecordingMailSender = RecordingMailSender()

    @Bean
    @Primary
    fun recordingSendMailPort(
        recorder: RecordingMailSender,
        metrics: RecordMetricsPort,
    ): SendMailPort = AfterCommitMailSender(recorder, metrics) { it.run() }
}
