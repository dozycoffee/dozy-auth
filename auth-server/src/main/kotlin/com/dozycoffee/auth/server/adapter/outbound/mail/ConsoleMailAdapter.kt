package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.server.application.port.outbound.mail.Mail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import org.slf4j.LoggerFactory

/**
 * 메일을 보내지 않고 텍스트 본문을 로그로 출력합니다 (`AUTH_MAIL_SENDER=console`). `prod`에서는 기동에 실패합니다 (configuration.md §2).
 *
 * 링크의 토큰은 [revealToken]일 때만 그대로 출력합니다. `local` 프로필에서만 켜며, 그 밖에서는 가립니다 (configuration.md §7, SEC-03).
 */
class ConsoleMailAdapter(
    private val renderer: MailRenderer,
    internal val revealToken: Boolean,
) : SendMailPort {
    override fun send(mail: Mail) {
        val rendered = renderer.render(mail, revealToken)
        log.info("메일 (발송하지 않음)\nTo: {}\nSubject: {}\n\n{}", mail.to.value, rendered.subject, rendered.text)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ConsoleMailAdapter::class.java)
    }
}
