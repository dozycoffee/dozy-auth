package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.server.support.MailFixtures.APP_URL
import com.dozycoffee.auth.server.support.MailFixtures.INTERNAL_APP
import com.dozycoffee.auth.server.support.MailFixtures.RECIPIENT
import com.dozycoffee.auth.server.support.MailFixtures.TOKEN
import com.dozycoffee.auth.server.support.MailFixtures.invitationMail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertContains
import kotlin.test.assertFalse

@ExtendWith(OutputCaptureExtension::class)
class ConsoleMailAdapterTest {
    @Test
    fun `SEC-03 토큰 출력을 켜지 않으면 링크의 토큰을 가려 출력함`(output: CapturedOutput) {
        ConsoleMailAdapter(MailRenderer(APP_URL), revealToken = false).send(invitationMail())

        assertContains(output.all, RECIPIENT.value)
        assertContains(output.all, "[Dozy Coffee] 직원 계정 초대")
        assertContains(output.all, "$INTERNAL_APP/invitation?token=***")
        assertFalse(output.all.contains(TOKEN.value))
    }

    @Test
    fun `토큰 출력을 켜면 링크 전체를 출력함`(output: CapturedOutput) {
        ConsoleMailAdapter(MailRenderer(APP_URL), revealToken = true).send(invitationMail())

        assertContains(output.all, "$INTERNAL_APP/invitation?token=${TOKEN.value}")
    }
}
