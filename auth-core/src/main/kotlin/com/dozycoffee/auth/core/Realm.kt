package com.dozycoffee.auth.core

/**
 * 인증 도메인. 로그인 방식, 계정 저장소, 토큰 issuer의 경계입니다.
 *
 * 토큰의 `iss`와 API 경로에는 [pathValue](소문자)로 나타납니다.
 */
public enum class Realm(
    /** `iss`와 API 경로(`/realms/{realm}`)에 쓰는 값. */
    public val pathValue: String,
) {
    /** 본사 직원과 마이크로서비스. */
    INTERNAL("internal"),

    /** 점주. */
    PARTNER("partner"),

    /** 고객 (보류). */
    CUSTOMER("customer"),
    ;

    /** 이 realm이 [type]의 principal을 받을 수 있는지 판단합니다 (DOM-01). */
    public fun allows(type: PrincipalType): Boolean = type.realm == this

    /**
     * 이 realm의 issuer를 만듭니다. 형식은 `{issuerBaseUri}/realms/{realm}`입니다.
     *
     * @param issuerBaseUri Auth 주소 (예: `https://auth.dozycoffee.com`). 끝의 `/`는 무시합니다.
     */
    public fun issuer(issuerBaseUri: String): String = "${issuerBaseUri.trimEnd('/')}/realms/$pathValue"

    public companion object {
        /**
         * [pathValue]로 realm을 찾습니다.
         *
         * @throws IllegalArgumentException 모르는 값일 때
         */
        public fun fromPathValue(value: String): Realm =
            fromPathValueOrNull(value) ?: throw IllegalArgumentException("알 수 없는 realm: $value")

        /** [pathValue]로 realm을 찾습니다. 모르는 값이면 `null`입니다. */
        public fun fromPathValueOrNull(value: String): Realm? = entries.firstOrNull { it.pathValue == value }
    }
}
