package com.dozycoffee.auth.starter

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * system token 클라이언트 설정 (starter.md §2, §6). [DozyAuthProperties]와 따로 둡니다. 설정 클래스에 속성을 더하면 공개 생성자와
 * `copy`의 시그니처가 바뀌기 때문입니다.
 *
 * 필수 값 검사는 바인딩이 아니라 클라이언트 자동 설정이 만들어질 때 합니다. 바인딩 중에 실패하면 Spring Boot의 기동 실패 보고가
 * 마지막으로 바인딩한 속성 값을 출력하는데, 그 값이 client secret일 수 있기 때문입니다 (SEC-03).
 * 같은 이유로 `toString`은 secret을 가립니다.
 */
@ConfigurationProperties(prefix = "dozy.auth.client")
public class DozyAuthClientProperties(
    /** system token 클라이언트 사용. */
    public val enabled: Boolean = false,
    /** system client id (CLI-01 `svc-{서비스명}`). */
    public val clientId: String = "",
    /** system client secret. 비밀 관리 도구에서 주입합니다. */
    public val clientSecret: String = "",
) {
    /** 클라이언트를 쓸 때 필수 값을 검사합니다. 메시지에 secret 값을 넣지 않습니다. */
    internal fun validate() {
        require(clientId.isNotBlank()) { "dozy.auth.client.enabled=true이면 dozy.auth.client.client-id를 설정해야 합니다" }
        require(clientSecret.isNotBlank()) { "dozy.auth.client.enabled=true이면 dozy.auth.client.client-secret을 설정해야 합니다" }
    }

    override fun toString(): String = "DozyAuthClientProperties(enabled=$enabled, clientId=$clientId, clientSecret=****)"
}
