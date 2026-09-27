package com.dozycoffee.auth.server.adapter.out.jwt

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * 서명 키 설정 (configuration.md §1, §3).
 *
 * @property keysDir PEM 파일이 있는 폴더 (`AUTH_SIGNING_KEYS_DIR`)
 * @property activeKid 서명에 쓸 키 (`AUTH_SIGNING_ACTIVE_KID`). 자동 생성이 켜져 있으면 비워도 됨
 * @property autoGenerate 키가 없으면 만들어 저장할지. local·test 전용이며 prod에서는 기동 실패
 */
@ConfigurationProperties("dozy.auth.signing")
data class SigningKeyProperties(
    val keysDir: Path,
    val activeKid: String? = null,
    val autoGenerate: Boolean = false,
)
