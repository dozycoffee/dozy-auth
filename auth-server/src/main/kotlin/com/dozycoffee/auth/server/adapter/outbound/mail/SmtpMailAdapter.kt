package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.server.application.port.outbound.mail.Mail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper

/**
 * SMTP로 메일을 보냅니다 (`AUTH_MAIL_SENDER=smtp`). 텍스트와 HTML 본문을 함께 담은 `multipart/alternative`입니다.
 *
 * 호출 즉시 보냅니다. 커밋 후 발송과 실패 처리는 [AfterCommitMailSender]가 맡습니다.
 *
 * @param from 보내는 주소 (`AUTH_MAIL_FROM`)
 */
class SmtpMailAdapter(
    private val mailSender: JavaMailSender,
    private val renderer: MailRenderer,
    private val from: String,
) : SendMailPort {
    override fun send(mail: Mail) {
        val rendered = renderer.render(mail)
        val message = mailSender.createMimeMessage()
        MimeMessageHelper(message, true, Charsets.UTF_8.name()).apply {
            setFrom(from, SENDER_NAME)
            setTo(mail.to.value)
            setSubject(rendered.subject)
            setText(rendered.text, rendered.html)
        }
        mailSender.send(message)
    }

    private companion object {
        const val SENDER_NAME = "Dozy Coffee"
    }
}
