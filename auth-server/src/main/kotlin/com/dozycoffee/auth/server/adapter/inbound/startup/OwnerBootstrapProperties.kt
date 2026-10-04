package com.dozycoffee.auth.server.adapter.inbound.startup

import com.dozycoffee.auth.server.domain.Email
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * owner 부트스트랩 설정 (configuration.md §1, §4, GOV-11). 이메일 형식이 틀리면 기동에 실패합니다.
 *
 * @property enabled 기동할 때 부트스트랩을 실행할지. `test` 프로필만 끄고, 부트스트랩 테스트에서 켭니다
 * @property ownerEmail `BOOTSTRAP_OWNER_EMAIL`. 비어 있으면 설정하지 않은 것으로 봅니다
 */
@ConfigurationProperties("dozy.auth.bootstrap")
data class OwnerBootstrapProperties(
    val enabled: Boolean = true,
    val ownerEmail: String? = null,
) {
    /** 설정한 이메일. 비어 있으면 `null`입니다. */
    val email: Email? = ownerEmail?.trim()?.takeIf { it.isNotEmpty() }?.let(::Email) // 형식이 틀리면 IllegalArgumentException
}
