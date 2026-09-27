package com.dozycoffee.auth.server.domain.token

/**
 * issuer 기준 주소 (`AUTH_ISSUER_BASE_URL`). 뒤에 `/realms/{realm}`을 붙여 `iss`를 만듭니다 (token.md §3).
 *
 * 끝의 `/`는 떼어 냅니다.
 */
class IssuerBaseUri(
    value: String,
) {
    val value: String = value.trimEnd('/')

    init {
        require(this.value.startsWith("https://") || this.value.startsWith("http://")) {
            "issuer 기준 주소는 http:// 또는 https://로 시작해야 합니다: $value"
        }
    }

    override fun equals(other: Any?): Boolean = other is IssuerBaseUri && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}
