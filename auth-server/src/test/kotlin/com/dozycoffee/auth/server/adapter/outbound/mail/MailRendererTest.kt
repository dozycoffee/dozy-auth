package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerNotificationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferCompletedMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferRequestMail
import com.dozycoffee.auth.server.application.port.outbound.mail.PasswordResetMail
import com.dozycoffee.auth.server.domain.audit.AuditAction
import com.dozycoffee.auth.server.support.MailFixtures.APP_URL
import com.dozycoffee.auth.server.support.MailFixtures.EXPIRES_AT
import com.dozycoffee.auth.server.support.MailFixtures.INTERNAL_APP
import com.dozycoffee.auth.server.support.MailFixtures.PARTNER_APP
import com.dozycoffee.auth.server.support.MailFixtures.RECIPIENT
import com.dozycoffee.auth.server.support.MailFixtures.TOKEN
import com.dozycoffee.auth.server.support.MailFixtures.invitationMail
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 메일 템플릿 렌더링. 링크 경로는 api/account.md의 링크 표 그대로입니다.
 * 시각은 한국 시간으로 보여 줍니다 (`EXPIRES_AT`은 한국 시간 2026-09-27 14:00).
 */
class MailRendererTest {
    private val renderer = MailRenderer(APP_URL)

    @Test
    fun `VER-05 직원 초대 링크는 관리 콘솔의 초대 화면 주소에 토큰을 담음`() {
        val rendered = renderer.render(invitationMail(name = "김직원"))

        val link = "$INTERNAL_APP/invitation?token=${TOKEN.value}"
        assertEquals("[Dozy Coffee] 직원 계정 초대", rendered.subject)
        assertContains(rendered.text, link)
        assertContains(rendered.text, "김직원님")
        assertContains(rendered.text, "2026-09-27 14:00")
        assertContains(rendered.html, "href=\"$link\"")
        assertContains(rendered.html, "김직원")
        assertContains(rendered.html, "2026-09-27 14:00")
    }

    @Test
    fun `VER-05 비밀번호 재설정 링크는 realm에 맞는 앱의 재설정 화면 주소에 토큰을 담음`() {
        val internal = renderer.render(PasswordResetMail(RECIPIENT, Realm.INTERNAL, TOKEN, EXPIRES_AT))
        val partner = renderer.render(PasswordResetMail(RECIPIENT, Realm.PARTNER, TOKEN, EXPIRES_AT))

        assertEquals("[Dozy Coffee] 비밀번호 재설정", internal.subject)
        assertContains(internal.text, "$INTERNAL_APP/password/reset?token=${TOKEN.value}")
        assertContains(internal.html, "href=\"$INTERNAL_APP/password/reset?token=${TOKEN.value}\"")
        assertContains(partner.text, "$PARTNER_APP/password/reset?token=${TOKEN.value}")
        assertContains(partner.html, "href=\"$PARTNER_APP/password/reset?token=${TOKEN.value}\"")
        assertContains(internal.text, "2026-09-27 14:00")
    }

    @Test
    fun `VER-05 owner 양도 요청 링크는 관리 콘솔의 양도 수락 화면 주소에 토큰을 담음`() {
        val rendered = renderer.render(OwnerTransferRequestMail(RECIPIENT, "박직원", TOKEN, EXPIRES_AT))

        val link = "$INTERNAL_APP/owner-transfer?token=${TOKEN.value}"
        assertEquals("[Dozy Coffee] owner 권한 양도 요청", rendered.subject)
        assertContains(rendered.text, link)
        assertContains(rendered.text, "박직원님")
        assertContains(rendered.html, "href=\"$link\"")
        assertContains(rendered.text, "2026-09-27 14:00")
    }

    @Test
    fun `AUD-03 양도 완료 메일에 새 owner와 완료 시각을 담음`() {
        val rendered = renderer.render(OwnerTransferCompletedMail(RECIPIENT, "박직원", EXPIRES_AT))

        assertEquals("[Dozy Coffee] owner 권한 양도 완료", rendered.subject)
        assertContains(rendered.text, "박직원님에게 넘어갔습니다")
        assertContains(rendered.text, "2026-09-27 14:00")
        assertContains(rendered.html, "박직원")
    }

    @Test
    fun `AUD-03 즉시 알림 메일에 action 설명과 발생 시각, 관리 콘솔 주소를 담음`() {
        val rendered = renderer.render(OwnerNotificationMail(RECIPIENT, AuditAction.AUDIENCE_CREATED, EXPIRES_AT))

        assertEquals("[Dozy Coffee] 관리 알림: audience 추가", rendered.subject)
        assertContains(rendered.text, "작업: audience 추가")
        assertContains(rendered.text, "시각: 2026-09-27 14:00")
        assertContains(rendered.text, INTERNAL_APP)
        assertContains(rendered.html, "href=\"$INTERNAL_APP\"")
    }

    @Test
    fun `HTML 본문은 이름의 HTML을 이스케이프함`() {
        val rendered = renderer.render(invitationMail(name = "<b>김</b>"))

        assertContains(rendered.html, "&lt;b&gt;김&lt;/b&gt;")
        assertFalse(rendered.html.contains("<b>김</b>"))
    }

    @Test
    fun `SEC-03 토큰을 가리면 링크에 토큰 원문이 없음`() {
        val rendered = renderer.render(invitationMail(), revealToken = false)

        assertContains(rendered.text, "$INTERNAL_APP/invitation?token=***")
        assertFalse(rendered.text.contains(TOKEN.value))
        assertFalse(rendered.html.contains(TOKEN.value))
    }

    @Test
    fun `SEC-03 렌더링 결과의 문자열 표현에 본문이 나오지 않음`() {
        val rendered = renderer.render(invitationMail())

        assertFalse(rendered.toString().contains(TOKEN.value))
    }

    @Test
    fun `앱 주소 끝의 슬래시는 링크에서 겹치지 않음`() {
        val slashRenderer =
            MailRenderer(MailSenderProperties.AppUrl(URI.create("$INTERNAL_APP/"), URI.create("$PARTNER_APP/")))

        val rendered = slashRenderer.render(invitationMail())

        assertTrue(rendered.text.contains("$INTERNAL_APP/invitation?token="))
    }
}
