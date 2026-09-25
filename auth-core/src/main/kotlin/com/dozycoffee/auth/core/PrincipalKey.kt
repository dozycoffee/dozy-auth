package com.dozycoffee.auth.core

import java.util.UUID

/**
 * 주체 식별자. 서비스는 항상 이 `(type, id)` 쌍으로 주체를 다룹니다.
 *
 * 토큰에서는 `sub`([sub]), `principalType`, `principalId` claim으로 나타납니다.
 *
 * @property type 계정 종류
 * @property id `principal` 테이블의 id. Auth는 UUIDv7로 발급하지만, 형식만 맞으면 버전은 따지지 않습니다 (ADR-0028)
 */
public data class PrincipalKey(
    public val type: PrincipalType,
    public val id: UUID,
) {
    /** `sub` claim 값. 형식은 `{type}:{id}`이고 id는 소문자 정규형입니다 (예: `employee:0199a3c4-7b2e-7c1a-9f3d-2b6e8a1c4d5f`). */
    public val sub: String
        get() = "${type.claimValue}$SEPARATOR$id"

    public companion object {
        private const val SEPARATOR = ':'

        /** 소문자·하이픈 포함 정규형 UUID (8-4-4-4-12자리 16진수). */
        private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * `principalId` claim 값을 파싱합니다. 소문자·하이픈 포함 정규형 UUID만 받습니다.
         *
         * @throws IllegalArgumentException 형식이 맞지 않을 때
         */
        public fun parseId(value: String): UUID =
            parseIdOrNull(value) ?: throw IllegalArgumentException("principal id 형식이 올바르지 않습니다: $value")

        /**
         * `principalId` claim 값을 파싱합니다. 형식이 맞지 않으면 `null`입니다.
         *
         * [UUID.fromString]은 `1-1-1-1-1` 같은 짧은 형식과 대문자도 받아들이므로, 형식을 먼저 검사해서 정규형만 받습니다.
         */
        public fun parseIdOrNull(value: String): UUID? = if (ID_PATTERN.matches(value)) UUID.fromString(value) else null

        /**
         * `sub` 값을 파싱합니다. 정규형(`{type}:{id}`)만 받습니다.
         *
         * @throws IllegalArgumentException 형식이 맞지 않을 때
         */
        public fun fromSub(sub: String): PrincipalKey = fromSubOrNull(sub) ?: throw IllegalArgumentException("sub 형식이 올바르지 않습니다: $sub")

        /**
         * `sub` 값을 파싱합니다. 형식이 맞지 않으면 `null`입니다.
         *
         * 정규형만 받습니다. 결과의 [sub]는 항상 입력과 같습니다. `EMPLOYEE:...`나 대문자 UUID는 거부합니다.
         */
        public fun fromSubOrNull(sub: String): PrincipalKey? {
            val separatorIndex = sub.indexOf(SEPARATOR)
            if (separatorIndex < 0) return null

            val type = PrincipalType.fromClaimValueOrNull(sub.substring(0, separatorIndex)) ?: return null
            val id = parseIdOrNull(sub.substring(separatorIndex + 1)) ?: return null
            return PrincipalKey(type, id)
        }
    }
}
