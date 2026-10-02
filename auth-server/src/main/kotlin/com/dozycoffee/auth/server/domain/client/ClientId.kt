package com.dozycoffee.auth.server.domain.client

/**
 * system client의 `client_id` (CLI-01). `svc-{서비스명}` 형식이며 서비스명은 소문자로 시작하는 소문자·숫자이고 하이픈으로 단어를 나눌 수 있습니다.
 *
 * 비활성화된 client의 `client_id`(`deleted-{id}`, ACC-04)는 이 형식이 아니므로 [ClientId]로 만들 수 없고, 인증에도 쓰이지 않습니다.
 */
@JvmInline
value class ClientId(
    val value: String,
) {
    init {
        require(isValid(value)) { "CLI-01 client_id는 svc-{서비스명} 형식이어야 합니다" }
    }

    override fun toString(): String = value

    companion object {
        /** `system_client.client_id`의 길이 (data-model.md §3.4). */
        const val MAX_LENGTH: Int = 100

        private val PATTERN = Regex("svc-[a-z][a-z0-9]*(-[a-z0-9]+)*")

        fun isValid(value: String): Boolean = value.length <= MAX_LENGTH && PATTERN.matches(value)

        /** 요청으로 받은 값처럼 형식을 모르는 값에 씁니다. 형식이 틀리면 `null`입니다. */
        fun parseOrNull(value: String): ClientId? = if (isValid(value)) ClientId(value) else null
    }
}
