package com.dozycoffee.auth.server.domain.credential

import com.dozycoffee.auth.server.domain.AuthPolicy
import com.dozycoffee.auth.server.domain.Email
import java.util.Locale

/**
 * 새 비밀번호 규칙 (domain.md §4). 가입, 초대 수락, 비밀번호 변경·재설정에서 해시하기 전에 검사합니다.
 *
 * - PWD-01 길이는 유니코드 코드 포인트 수로 셉니다. 이모지처럼 UTF-16 두 단위로 표현되는 문자도 한 글자입니다.
 *   정규화(NFC 등)나 앞뒤 공백 제거 없이 입력 그대로 셉니다.
 * - PWD-02 문자 조합(대소문자, 숫자, 기호)은 검사하지 않습니다.
 * - PWD-03 이메일과 같은지는 대소문자를 구분하지 않고 비교합니다. 이메일이 대소문자를 구분하지 않는 식별자이기 때문입니다 ([Email.lookupKey]).
 */
object PasswordPolicy {
    /** 규칙을 어기면 [PasswordPolicyViolationException] (`400 VALIDATION_FAILED`). */
    fun check(
        password: RawPassword,
        email: Email,
    ) {
        val length = password.value.codePointCount(0, password.value.length)
        if (length !in AuthPolicy.PASSWORD_MIN_LENGTH..AuthPolicy.PASSWORD_MAX_LENGTH) {
            throw PasswordPolicyViolationException(
                "비밀번호는 ${AuthPolicy.PASSWORD_MIN_LENGTH}자 이상 ${AuthPolicy.PASSWORD_MAX_LENGTH}자 이하여야 합니다.",
            )
        }
        if (password.value.lowercase(Locale.ROOT) == email.lookupKey) {
            throw PasswordPolicyViolationException("이메일과 같은 비밀번호는 쓸 수 없습니다.")
        }
    }
}
