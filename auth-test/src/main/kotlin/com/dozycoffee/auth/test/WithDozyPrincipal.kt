package com.dozycoffee.auth.test

import com.dozycoffee.auth.core.PrincipalType
import org.springframework.security.test.context.support.WithSecurityContext
import java.lang.annotation.Inherited

/**
 * 컨트롤러 테스트에서 인증된 사용자를 만듭니다 (starter.md §7.1). JWT를 검증하지 않고 SecurityContext에 바로 넣습니다.
 *
 * MockMvc와 `WebTestClient` 모두에서 동작합니다. role은 스타터의 권한 변환기로 바꾸므로 실제 토큰과 같은 규칙을 따릅니다.
 * realm은 [type]이 속한 realm(DOM-01)입니다.
 *
 * ```kotlin
 * @Test
 * @WithDozyPrincipal(roles = ["sample:item_manager"])
 * fun `상품 관리자는 상품을 등록할 수 있다`() { ... }
 * ```
 *
 * @property type principal type
 * @property id principal id (UUID 문자열). 애노테이션 속성은 `UUID` 타입을 쓸 수 없습니다
 * @property roles `{audience}:{code}` 형식
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@MustBeDocumented
@WithSecurityContext(factory = WithDozyPrincipalSecurityContextFactory::class)
public annotation class WithDozyPrincipal(
    val type: PrincipalType = PrincipalType.EMPLOYEE,
    val id: String = DEFAULT_ID,
    val roles: Array<String> = [],
) {
    public companion object {
        public const val DEFAULT_ID: String = "00000000-0000-7000-8000-000000000001"
    }
}
