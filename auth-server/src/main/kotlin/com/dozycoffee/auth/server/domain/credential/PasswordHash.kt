package com.dozycoffee.auth.server.domain.credential

/**
 * 비밀번호 해시의 인코딩 문자열 (PWD-04, data-model.md §3.5 `password_hash`).
 *
 * argon2id 파라미터(메모리, 반복 횟수, 병렬도)와 salt가 문자열에 들어 있으므로, 기본 파라미터를 바꿔도 기존 해시를 검증할 수 있습니다 (ADR-0027).
 * 해시도 오프라인 대입의 재료가 되므로 [toString]은 값을 가립니다.
 */
@JvmInline
value class PasswordHash(
    val encoded: String,
) {
    init {
        require(encoded.isNotBlank()) { "비밀번호 해시가 비어 있습니다" }
        require(encoded.length <= MAX_LENGTH) { "비밀번호 해시는 ${MAX_LENGTH}자 이하여야 합니다" }
    }

    override fun toString(): String = "PasswordHash(***)"

    companion object {
        /** `password_credential.password_hash` 컬럼 길이 (data-model.md §3.5). */
        const val MAX_LENGTH: Int = 255
    }
}
