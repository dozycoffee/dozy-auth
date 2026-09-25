package com.dozycoffee.auth.core

/**
 * 주체 식별자. 서비스는 항상 이 `(type, id)` 쌍으로 주체를 다룹니다.
 *
 * 토큰에서는 `sub`([sub]), `principalType`, `principalId` claim으로 나타납니다.
 *
 * @property type 계정 종류
 * @property id `principal` 테이블의 id. [MIN_ID] 이상 [MAX_ID] 이하
 * @throws IllegalArgumentException [id]가 범위를 벗어날 때
 */
public data class PrincipalKey(
    public val type: PrincipalType,
    public val id: Long,
) {
    init {
        require(id in MIN_ID..MAX_ID) { "principal id는 $MIN_ID 이상 $MAX_ID 이하여야 합니다: $id" }
    }

    /** `sub` claim 값. 형식은 `{type}:{id}`입니다 (예: `employee:42`). */
    public val sub: String
        get() = "${type.claimValue}$SEPARATOR$id"

    public companion object {
        /** principal id의 최솟값. */
        public const val MIN_ID: Long = 1

        /**
         * principal id의 최댓값. 2^53 - 1이며, JavaScript `Number.MAX_SAFE_INTEGER`와 같습니다.
         *
         * `principalId` claim은 JSON number라서, JavaScript에서 정밀도 손실 없이 읽을 수 있는 범위로 제한합니다 (token.md §3).
         */
        public const val MAX_ID: Long = (1L shl 53) - 1

        private const val SEPARATOR = ':'

        /** 앞자리 0, 부호, 공백이 없는 10진수. */
        private val ID_PATTERN = Regex("[1-9][0-9]*")

        /**
         * `sub` 값을 파싱합니다. 정규형(`{type}:{id}`)만 받습니다.
         *
         * @throws IllegalArgumentException 형식이 맞지 않을 때
         */
        public fun fromSub(sub: String): PrincipalKey = fromSubOrNull(sub) ?: throw IllegalArgumentException("sub 형식이 올바르지 않습니다: $sub")

        /**
         * `sub` 값을 파싱합니다. 형식이 맞지 않으면 `null`입니다.
         *
         * 정규형만 받습니다. 결과의 [sub]는 항상 입력과 같습니다. `employee:042`, `employee:+42`, `EMPLOYEE:42`는 거부합니다.
         */
        public fun fromSubOrNull(sub: String): PrincipalKey? {
            val separatorIndex = sub.indexOf(SEPARATOR)
            if (separatorIndex < 0) return null

            val type = PrincipalType.fromClaimValueOrNull(sub.substring(0, separatorIndex)) ?: return null
            val idPart = sub.substring(separatorIndex + 1)
            if (!ID_PATTERN.matches(idPart)) return null

            val id = idPart.toLongOrNull() ?: return null
            if (id > MAX_ID) return null
            return PrincipalKey(type, id)
        }
    }
}
