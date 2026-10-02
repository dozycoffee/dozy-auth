package com.dozycoffee.auth.server.domain

/**
 * SEC-02 응답에 내보내는 이메일·전화번호 마스킹. 초대 조회(VER-06)와 파트너 조회(INT-01)가 함께 씁니다.
 *
 * 규칙은 domain.md SEC-02에 있습니다.
 */
object Masking {
    private const val MASK = "***"
    private const val EMAIL_VISIBLE_PREFIX = 2
    private const val PHONE_VISIBLE_PREFIX = 3
    private const val PHONE_VISIBLE_SUFFIX = 4

    /** 가리는 숫자가 항상 네 자리 이상이 되도록, 앞자리는 숫자가 이만큼 이상일 때만 남깁니다. */
    private const val PHONE_MIN_DIGITS_FOR_PREFIX = 11

    /** 숫자가 이보다 적으면 모두 가립니다. */
    private const val PHONE_MIN_DIGITS_FOR_SUFFIX = 8

    /**
     * 로컬 부분의 앞 두 글자와 도메인만 남깁니다. 예: `kim@dozycoffee.com` → `ki***@dozycoffee.com`
     *
     * - 로컬 부분이 두 글자 이하이면 전체가 드러나지 않도록 마지막 글자는 가립니다 (`ab` → `a***`, `a` → `***`).
     * - `***`는 가린 글자 수와 관계없이 세 글자라 원래 길이가 드러나지 않습니다.
     * - 글자는 유니코드 코드 포인트 단위로 셉니다. 서로게이트 쌍을 자르지 않습니다.
     */
    fun email(email: Email): String {
        val local = email.value.substringBeforeLast('@')
        val domain = email.value.substringAfterLast('@')
        val length = local.codePointCount(0, local.length)
        val visible = minOf(EMAIL_VISIBLE_PREFIX, length - 1)
        val prefix = local.substring(0, local.offsetByCodePoints(0, visible))
        return "$prefix$MASK@$domain"
    }

    /**
     * 숫자의 앞 세 자리와 뒤 네 자리만 남기고 나머지 숫자를 `*`로 바꿉니다. 예: `010-1234-5678` → `010-****-5678`
     *
     * - 하이픈, 공백, 괄호, `+` 같은 숫자 아닌 글자는 자리를 그대로 둡니다. 숫자는 유니코드 숫자 전체(전각 숫자 포함)입니다.
     * - 가리는 숫자가 항상 네 자리 이상이 되게 합니다. 숫자가 11자리 미만이면 뒤 네 자리만 남기고(`02-1234-5678` → `**-****-5678`),
     *   8자리 미만이면 모두 가립니다(`123-4567` → `***-****`).
     */
    fun phone(phone: String): String {
        val digits = phone.count(Char::isDigit)
        val prefix = if (digits >= PHONE_MIN_DIGITS_FOR_PREFIX) PHONE_VISIBLE_PREFIX else 0
        val suffix = if (digits >= PHONE_MIN_DIGITS_FOR_SUFFIX) PHONE_VISIBLE_SUFFIX else 0
        var index = 0
        return buildString(phone.length) {
            phone.forEach { char ->
                if (!char.isDigit()) {
                    append(char)
                    return@forEach
                }
                append(if (index < prefix || index >= digits - suffix) char else '*')
                index++
            }
        }
    }
}
