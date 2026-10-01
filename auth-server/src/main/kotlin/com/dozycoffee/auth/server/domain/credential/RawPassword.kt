package com.dozycoffee.auth.server.domain.credential

/**
 * 사용자가 입력한 비밀번호 원문.
 *
 * 저장하지 않고 해시([PasswordHash])로만 남깁니다 (SEC-01). 로그나 예외 메시지에 원문이 섞이지 않도록 [toString]은 값을 가립니다 (SEC-03).
 * 형식은 검사하지 않습니다. 새 비밀번호는 [PasswordPolicy]로 검사하고, 로그인 입력은 정책과 관계없이 해시 검증만 합니다.
 */
@JvmInline
value class RawPassword(
    val value: String,
) {
    override fun toString(): String = "RawPassword(***)"
}
