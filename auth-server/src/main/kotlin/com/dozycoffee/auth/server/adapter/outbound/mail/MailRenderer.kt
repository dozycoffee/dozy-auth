package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.core.Realm
import com.dozycoffee.auth.server.application.port.outbound.mail.EmployeeInvitationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.Mail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerDailySummaryMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerNotificationMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferCompletedMail
import com.dozycoffee.auth.server.application.port.outbound.mail.OwnerTransferRequestMail
import com.dozycoffee.auth.server.application.port.outbound.mail.PasswordResetMail
import com.dozycoffee.auth.server.domain.OpaqueSecret
import com.dozycoffee.auth.server.domain.audit.AuditAction
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 메일 종류와 값을 제목, 텍스트 본문, HTML 본문으로 만듭니다. 문구는 `templates/mail/{종류}.txt`, `.html`에 있습니다.
 *
 * - 링크는 앱 화면 주소에 토큰을 쿼리로 붙여 만듭니다 (api/account.md의 링크 표, VER-05).
 * - 이름 같은 입력값은 Thymeleaf가 HTML 이스케이프합니다.
 * - 시각은 한국 시간으로 표시합니다.
 */
class MailRenderer(
    private val appUrl: MailSenderProperties.AppUrl,
) {
    /** @param revealToken `false`면 링크의 토큰을 [MASKED_TOKEN]으로 가립니다. 로그로 출력할 때 씁니다 (SEC-03) */
    fun render(
        mail: Mail,
        revealToken: Boolean = true,
    ): RenderedMail {
        val content = content(mail, revealToken)
        val context = Context(Locale.KOREAN, content.variables)
        return RenderedMail(
            subject = "$SUBJECT_PREFIX ${content.subject}",
            text = engine.process("${content.template}.txt", context),
            html = engine.process("${content.template}.html", context),
        )
    }

    private fun content(
        mail: Mail,
        revealToken: Boolean,
    ): Content =
        when (mail) {
            is EmployeeInvitationMail ->
                Content(
                    template = "employee-invitation",
                    subject = "직원 계정 초대",
                    variables =
                        mapOf(
                            "name" to mail.name,
                            "link" to link(appUrl.internal, INVITATION_PATH, mail.token, revealToken),
                            "expiresAt" to format(mail.expiresAt),
                        ),
                )

            is PasswordResetMail ->
                Content(
                    template = "password-reset",
                    subject = "비밀번호 재설정",
                    variables =
                        mapOf(
                            "link" to link(appBaseOf(mail.realm), PASSWORD_RESET_PATH, mail.token, revealToken),
                            "expiresAt" to format(mail.expiresAt),
                        ),
                )

            is OwnerTransferRequestMail ->
                Content(
                    template = "owner-transfer-request",
                    subject = "owner 권한 양도 요청",
                    variables =
                        mapOf(
                            "name" to mail.name,
                            "link" to link(appUrl.internal, OWNER_TRANSFER_PATH, mail.token, revealToken),
                            "expiresAt" to format(mail.expiresAt),
                        ),
                )

            is OwnerTransferCompletedMail ->
                Content(
                    template = "owner-transfer-completed",
                    subject = "owner 권한 양도 완료",
                    variables =
                        mapOf(
                            "newOwnerName" to mail.newOwnerName,
                            "transferredAt" to format(mail.transferredAt),
                        ),
                )

            is OwnerNotificationMail ->
                Content(
                    template = "owner-notification",
                    subject = "관리 알림: ${describe(mail.action)}",
                    variables =
                        mapOf(
                            "action" to describe(mail.action),
                            "occurredAt" to format(mail.occurredAt),
                            "consoleUrl" to base(appUrl.internal),
                        ),
                )

            is OwnerDailySummaryMail ->
                Content(
                    template = "owner-daily-summary",
                    subject = "일일 관리 요약 (${mail.date})",
                    variables =
                        mapOf(
                            "date" to mail.date.toString(),
                            "rows" to
                                mail.counts.entries
                                    .sortedBy { it.key.ordinal }
                                    .map { SummaryRow(describe(it.key), it.value) },
                            "consoleUrl" to base(appUrl.internal),
                        ),
                )
        }

    private fun appBaseOf(realm: Realm): URI =
        when (realm) {
            Realm.INTERNAL -> appUrl.internal
            Realm.PARTNER -> appUrl.partner
            Realm.CUSTOMER -> error("customer realm은 메일 링크 기준 주소가 없습니다")
        }

    private fun link(
        appBase: URI,
        path: String,
        token: OpaqueSecret,
        revealToken: Boolean,
    ): String = "${base(appBase)}$path?token=${if (revealToken) token.value else MASKED_TOKEN}"

    private fun base(appBase: URI): String = appBase.toString().trimEnd('/')

    private fun format(instant: Instant): String = "${DATE_TIME.format(instant)} (한국 시간)"

    private data class Content(
        val template: String,
        val subject: String,
        val variables: Map<String, Any>,
    )

    /** 일일 요약 표의 한 줄. 템플릿에서 프로퍼티로 읽습니다. */
    data class SummaryRow(
        val action: String,
        val count: Int,
    )

    companion object {
        /** 로그로 출력할 때 링크의 토큰 대신 넣는 값. */
        const val MASKED_TOKEN: String = "***"

        // 앱 화면 경로 (api/account.md의 링크 표)
        private const val INVITATION_PATH = "/invitation"
        private const val PASSWORD_RESET_PATH = "/password/reset"
        private const val OWNER_TRANSFER_PATH = "/owner-transfer"

        private const val SUBJECT_PREFIX = "[Dozy Coffee]"

        private val DATE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

        private val engine =
            SpringTemplateEngine().apply {
                setTemplateResolver(
                    ClassLoaderTemplateResolver().apply {
                        prefix = "templates/mail/"
                        characterEncoding = "UTF-8"
                        setHtmlTemplateModePatterns(setOf("*.html"))
                        setTextTemplateModePatterns(setOf("*.txt"))
                        isCacheable = true
                    },
                )
            }

        /** 알림 메일에 쓰는 action 설명. 새 action을 추가하면 컴파일러가 여기를 알려 줍니다. */
        fun describe(action: AuditAction): String =
            when (action) {
                AuditAction.LOGIN_SUCCEEDED -> "로그인"
                AuditAction.LOGIN_FAILED -> "로그인 실패"
                AuditAction.ACCOUNT_LOCKED -> "계정 잠금"
                AuditAction.SESSION_REVOKED -> "세션 폐기"
                AuditAction.PASSWORD_CHANGED -> "비밀번호 변경"
                AuditAction.PASSWORD_RESET -> "비밀번호 재설정"
                AuditAction.PASSWORD_RESET_REQUESTED -> "비밀번호 재설정 메일 발송"
                AuditAction.EMPLOYEE_INVITED -> "직원 초대"
                AuditAction.INVITATION_ACCEPTED -> "초대 수락"
                AuditAction.PARTNER_SIGNED_UP -> "파트너 가입"
                AuditAction.EMAIL_VERIFIED -> "이메일 인증"
                AuditAction.PROFILE_UPDATED -> "정보 수정"
                AuditAction.ACCOUNT_SUSPENDED -> "계정 정지"
                AuditAction.ACCOUNT_REACTIVATED -> "계정 정지 해제"
                AuditAction.ACCOUNT_DEACTIVATED -> "계정 비활성화"
                AuditAction.ROLE_GRANTED -> "role 부여"
                AuditAction.ROLE_REVOKED -> "role 회수"
                AuditAction.ROLE_DEFINED -> "role 정의 등록"
                AuditAction.ROLE_UPDATED -> "role 정의 수정"
                AuditAction.ROLE_DELETED -> "role 정의 삭제"
                AuditAction.AUDIENCE_CREATED -> "audience 추가"
                AuditAction.SYSTEM_CLIENT_REGISTERED -> "system client 등록"
                AuditAction.CLIENT_SECRET_ROTATED -> "client secret 교체"
                AuditAction.OWNER_TRANSFER_REQUESTED -> "owner 양도 요청"
                AuditAction.OWNER_TRANSFER_CANCELLED -> "owner 양도 취소"
                AuditAction.OWNER_TRANSFERRED -> "owner 양도 완료"
            }
    }
}

/** 렌더링한 메일. 본문에는 토큰이 든 링크가 있으므로 [toString]은 제목만 보여 줍니다 (SEC-03). */
class RenderedMail(
    val subject: String,
    val text: String,
    val html: String,
) {
    override fun toString(): String = "RenderedMail(subject=$subject)"
}
