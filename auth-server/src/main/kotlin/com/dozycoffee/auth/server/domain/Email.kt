package com.dozycoffee.auth.server.domain

import java.util.Locale

/**
 * 이메일 주소. 로그인 ID이자 메일 발송 대상입니다.
 *
 * 입력값을 그대로 보관하고, 유일성과 조회는 대소문자를 구분하지 않으므로 [lookupKey]로 합니다
 * (data-model.md §1, `lower(email)` 인덱스).
 *
 * 형식 검증은 요청 검증(`VALIDATION_FAILED`)이 맡습니다. 여기서는 저장할 수 없는 값만 막습니다.
 * 예외 메시지에는 주소를 넣지 않습니다.
 */
@JvmInline
value class Email(
    val value: String,
) {
    init {
        require(value.length <= MAX_LENGTH) { "이메일은 ${MAX_LENGTH}자 이하여야 합니다" }
        require(FORMAT.matches(value)) { "이메일 형식이 아닙니다" }
    }

    /** 조회와 유일성 비교에 쓰는 소문자 값. DB의 `lower(email)`과 같은 값입니다. */
    val lookupKey: String
        get() = value.lowercase(Locale.ROOT)

    companion object {
        /** `employee_profile.email` 컬럼 길이 (data-model.md §3.2). */
        const val MAX_LENGTH: Int = 254

        /** 공백 없이 `@` 하나로 나뉜 로컬 부분과 도메인. */
        private val FORMAT = Regex("[^\\s@]+@[^\\s@]+")
    }
}
