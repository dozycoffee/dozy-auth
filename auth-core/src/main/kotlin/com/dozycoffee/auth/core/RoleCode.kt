package com.dozycoffee.auth.core

/**
 * role 식별자. 형식은 `{audience}:{code}`입니다 (예: `wms:inbound_manager`).
 *
 * audience는 운영 중에 추가될 수 있어서 enum이 아니라 문자열입니다.
 *
 * @property audience role이 속한 서비스 (예: `wms`)
 * @property code audience 안의 role 이름 (예: `inbound_manager`)
 * @throws IllegalArgumentException [audience]나 [code]가 형식(DOM-03)에 맞지 않을 때
 */
public data class RoleCode(
    public val audience: String,
    public val code: String,
) {
    init {
        require(isValidCode(audience)) { "audience 형식이 올바르지 않습니다: $audience" }
        require(isValidCode(code)) { "role code 형식이 올바르지 않습니다: $code" }
    }

    /** 토큰의 `roles`에 쓰는 값. 형식은 `{audience}:{code}`입니다. */
    public val value: String
        get() = "$audience$SEPARATOR$code"

    public companion object {
        private const val SEPARATOR = ':'

        /** audience와 role code의 형식 (DOM-03). */
        private val CODE_PATTERN = Regex("[a-z][a-z0-9_]*")

        /** [value]가 audience나 role code 형식(DOM-03, `^[a-z][a-z0-9_]*$`)에 맞는지 판단합니다. */
        public fun isValidCode(value: String): Boolean = CODE_PATTERN.matches(value)

        /**
         * `{audience}:{code}` 문자열을 파싱합니다.
         *
         * @throws IllegalArgumentException 형식이 맞지 않을 때
         */
        public fun parse(value: String): RoleCode = parseOrNull(value) ?: throw IllegalArgumentException("role 형식이 올바르지 않습니다: $value")

        /** `{audience}:{code}` 문자열을 파싱합니다. 형식이 맞지 않으면 `null`입니다. */
        public fun parseOrNull(value: String): RoleCode? {
            val parts = value.split(SEPARATOR)
            if (parts.size != 2) return null

            val (audience, code) = parts
            if (!isValidCode(audience) || !isValidCode(code)) return null
            return RoleCode(audience, code)
        }
    }
}
