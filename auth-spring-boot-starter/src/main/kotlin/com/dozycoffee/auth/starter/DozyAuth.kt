package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.PrincipalType
import org.springframework.security.core.context.SecurityContextHolder

/**
 * SpEL과 코드에서 쓰는 인가 도구. `dozyAuth`라는 이름의 빈으로 등록됩니다 (starter.md §4).
 *
 * ```kotlin
 * @PreAuthorize("@dozyAuth.isType('PARTNER')")
 * ```
 */
public class DozyAuth {
    /**
     * 현재 principal type이 [type]이면 true. 인증 정보가 없으면 false.
     *
     * @param type [PrincipalType] 이름 (`EMPLOYEE`, `SYSTEM`, `PARTNER`, `CUSTOMER`). 다른 값이면 [IllegalArgumentException]
     */
    public fun isType(type: String): Boolean {
        val expected = PrincipalType.valueOf(type)
        return currentPrincipal()?.key?.type == expected
    }

    private fun currentPrincipal(): AuthenticatedPrincipal? =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal

    public companion object {
        /** SpEL에서 쓰는 빈 이름. */
        public const val BEAN_NAME: String = "dozyAuth"
    }
}
