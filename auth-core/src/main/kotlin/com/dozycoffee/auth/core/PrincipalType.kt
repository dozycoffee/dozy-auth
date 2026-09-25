package com.dozycoffee.auth.core

/**
 * 계정의 종류. 계정을 만든 뒤에는 바뀌지 않습니다.
 *
 * 토큰의 `principalType` claim과 `sub`에는 [claimValue](소문자)로, DB에는 [name](대문자)으로 저장합니다.
 */
public enum class PrincipalType(
    /** `principalType` claim과 `sub`에 쓰는 값. */
    public val claimValue: String,
) {
    /** 본사 직원. */
    EMPLOYEE("employee"),

    /** 마이크로서비스 (system client). */
    SYSTEM("system"),

    /** 점주. */
    PARTNER("partner"),

    /** 고객 (보류). */
    CUSTOMER("customer"),
    ;

    /** 이 종류의 계정이 속한 realm (DOM-01). 한 종류는 realm 하나에만 속합니다. */
    public val realm: Realm
        get() =
            when (this) {
                EMPLOYEE, SYSTEM -> Realm.INTERNAL
                PARTNER -> Realm.PARTNER
                CUSTOMER -> Realm.CUSTOMER
            }

    public companion object {
        /**
         * [claimValue]로 종류를 찾습니다.
         *
         * @throws IllegalArgumentException 모르는 값일 때
         */
        public fun fromClaimValue(value: String): PrincipalType =
            fromClaimValueOrNull(value) ?: throw IllegalArgumentException("알 수 없는 principal type: $value")

        /** [claimValue]로 종류를 찾습니다. 모르는 값이면 `null`입니다. */
        public fun fromClaimValueOrNull(value: String): PrincipalType? = entries.firstOrNull { it.claimValue == value }
    }
}
