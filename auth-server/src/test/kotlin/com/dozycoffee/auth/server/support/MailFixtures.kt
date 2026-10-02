package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.server.adapter.outbound.mail.MailSenderProperties
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.Mail
import com.dozycoffee.auth.server.application.port.outbound.mail.SendMailPort
import com.dozycoffee.auth.server.domain.Email
import com.dozycoffee.auth.server.domain.OpaqueSecret
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** 메일 테스트 입력. 기대값은 각 테스트에 씁니다 (testing.md §3). */
object MailFixtures {
    const val INTERNAL_APP = "https://admin.dozycoffee.test"
    const val PARTNER_APP = "https://partner.dozycoffee.test"

    val APP_URL = MailSenderProperties.AppUrl(URI.create(INTERNAL_APP), URI.create(PARTNER_APP))

    val RECIPIENT = Email("kim@dozycoffee.test")

    val TOKEN: OpaqueSecret = OpaqueSecret.generate()

    /** 한국 시간으로 2026-09-27 14:00. */
    val EXPIRES_AT: Instant = Instant.parse("2026-09-27T05:00:00Z")

    fun invitationMail(
        to: Email = RECIPIENT,
        name: String = "김직원",
    ): EmployeeInvitationMail = EmployeeInvitationMail(to, name, TOKEN, EXPIRES_AT)
}

/** 보내는 대신 기록하는 [SendMailPort] 테스트 대역. 여러 스레드에서 불려도 됩니다. */
class RecordingMailSender : SendMailPort {
    val sent: MutableList<Mail> = CopyOnWriteArrayList()

    override fun send(mail: Mail) {
        sent += mail
    }
}
