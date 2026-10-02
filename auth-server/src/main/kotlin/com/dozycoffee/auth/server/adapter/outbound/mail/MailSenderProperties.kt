package com.dozycoffee.auth.server.adapter.outbound.mail

import com.dozycoffee.auth.server.domain.Email
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * 메일 발송 설정 (configuration.md §1, §7). 값이 없거나 형식이 틀리면 기동에 실패합니다.
 *
 * SMTP 접속 정보는 Spring Boot의 `spring.mail.*`를 그대로 씁니다.
 *
 * @property sender 발송 방식. `AUTH_MAIL_SENDER`
 * @property from 보내는 주소. `AUTH_MAIL_FROM`
 * @property appUrl 메일 링크의 기준 주소 (앱 화면 주소, VER-05)
 */
@ConfigurationProperties("dozy.auth.mail")
data class MailSenderProperties(
    val sender: MailSenderType,
    val from: String,
    val appUrl: AppUrl,
) {
    init {
        Email(from) // 형식이 틀리면 IllegalArgumentException
    }

    /**
     * realm별 앱 주소. 링크는 이 주소 뒤에 화면 경로를 붙여 만들며, 끝의 `/`는 무시합니다.
     *
     * @property internal 관리 콘솔. `AUTH_APP_URL_INTERNAL`
     * @property partner 파트너 웹. `AUTH_APP_URL_PARTNER`
     */
    data class AppUrl(
        val internal: URI,
        val partner: URI,
    ) {
        init {
            requireAppUrl(internal, "internal")
            requireAppUrl(partner, "partner")
        }

        private fun requireAppUrl(
            uri: URI,
            name: String,
        ) {
            require(uri.scheme == "https" || uri.scheme == "http") { "dozy.auth.mail.app-url.$name 은 http(s) 주소여야 합니다" }
            require(!uri.host.isNullOrBlank()) { "dozy.auth.mail.app-url.$name 에 host가 없습니다" }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "dozy.auth.mail.app-url.$name 에는 쿼리나 fragment를 넣지 않습니다"
            }
        }
    }
}

/** 메일 발송 방식 (`AUTH_MAIL_SENDER`). */
enum class MailSenderType {
    /** `spring.mail.*`의 SMTP 서버로 보냅니다. */
    SMTP,

    /** 보내지 않고 로그로 출력합니다. local·dev 전용이며 `prod`에서는 기동에 실패합니다 (configuration.md §2). */
    CONSOLE,
}
