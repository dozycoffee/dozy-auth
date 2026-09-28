package com.dozycoffee.auth.starter

import com.dozycoffee.auth.core.AuthenticatedPrincipal
import com.dozycoffee.auth.core.PrincipalType
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono

/**
 * WebFlux용 인가 도구. [DozyAuth]와 같은 `dozyAuth` 이름으로 등록되어 SpEL 사용법이 같습니다 (starter.md §4).
 *
 * ```kotlin
 * @PreAuthorize("@dozyAuth.isType('PARTNER')")
 * suspend fun myStores(...)
 * ```
 *
 * WebFlux에는 thread-local 보안 컨텍스트가 없어서 결과를 [Mono]로 돌려줍니다. reactive 메서드 보안은 `Mono<Boolean>` 결과를 기다려 판단합니다.
 */
public class DozyReactiveAuth {
    /**
     * 현재 principal type이 [type]이면 true. 인증 정보가 없으면 false.
     *
     * @param type [PrincipalType] 이름 (`EMPLOYEE`, `SYSTEM`, `PARTNER`, `CUSTOMER`). 다른 값이면 [IllegalArgumentException]
     */
    public fun isType(type: String): Mono<Boolean> {
        val expected = PrincipalType.valueOf(type)
        return ReactiveSecurityContextHolder
            .getContext()
            .mapNotNull { it.authentication?.principal as? AuthenticatedPrincipal }
            .map { it.key.type == expected }
            .defaultIfEmpty(false)
    }
}
