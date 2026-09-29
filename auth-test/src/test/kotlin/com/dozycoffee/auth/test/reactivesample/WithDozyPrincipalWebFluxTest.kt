package com.dozycoffee.auth.test.reactivesample

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.test.WithDozyPrincipal
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.test.web.reactive.server.WebTestClient

/** WebFlux 슬라이스 테스트에서 `@WithDozyPrincipal` (starter.md §7.1). 서비스의 컨트롤러 단위 테스트 방식입니다. */
@WebFluxTest(ItemController::class)
class WithDozyPrincipalWebFluxTest {
    @Autowired
    lateinit var client: WebTestClient

    @Test
    @WithDozyPrincipal(roles = ["sample:item_manager"])
    fun `자기 audience의 role이 있으면 PreAuthorize 통과`() {
        client
            .get()
            .uri("/items")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    @WithDozyPrincipal(roles = ["sample:item_viewer"])
    fun `필요한 role이 없으면 403`() {
        client
            .get()
            .uri("/items")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    @WithDozyPrincipal(roles = ["other:item_manager"])
    fun `다른 audience의 role은 스타터 규칙대로 무시`() {
        client
            .get()
            .uri("/items")
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    @WithDozyPrincipal(
        type = PrincipalType.SYSTEM,
        id = "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
        roles = ["sample:item_manager", "other:item_editor"],
    )
    fun `CurrentPrincipal에 type·id와 type이 속한 realm, 자기 audience의 role이 담김`() {
        client
            .get()
            .uri("/me")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.sub")
            .isEqualTo("system:0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
            .jsonPath("$.realm")
            .isEqualTo("INTERNAL")
            .jsonPath("$.roles.length()")
            .isEqualTo(1)
            .jsonPath("$.roles[0]")
            .isEqualTo("item_manager")
    }

    @Test
    @WithDozyPrincipal
    fun `기본값은 role 없는 직원`() {
        client
            .get()
            .uri("/me")
            .exchange()
            .expectBody()
            .jsonPath("$.sub")
            .isEqualTo("employee:${WithDozyPrincipal.DEFAULT_ID}")
            .jsonPath("$.roles.length()")
            .isEqualTo(0)
        client
            .get()
            .uri("/employees-only")
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `애노테이션이 없으면 401`() {
        client
            .get()
            .uri("/me")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }
}
