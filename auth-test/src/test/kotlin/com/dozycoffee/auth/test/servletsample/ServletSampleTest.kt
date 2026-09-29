package com.dozycoffee.auth.test.servletsample

import com.dozycoffee.auth.core.PrincipalType
import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.auth.test.WithDozyPrincipal
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Spring MVC 서비스에서 두 도구가 동작하는지 대표 경우만 확인합니다. 자세한 경우는 WebFlux 테스트에 있습니다. */
class ServletSampleTest {
    @Nested
    @WebMvcTest(ServletItemController::class)
    inner class Annotation {
        @Autowired
        lateinit var mockMvc: MockMvc

        @Test
        @WithDozyPrincipal(roles = ["sample:item_manager"])
        fun `WithDozyPrincipal의 role로 인가`() {
            mockMvc.get("/items").andExpect { status { isOk() } }
        }

        @Test
        @WithDozyPrincipal(roles = ["sample:item_viewer"])
        fun `WithDozyPrincipal에 필요한 role이 없으면 403`() {
            mockMvc.get("/items").andExpect { status { isForbidden() } }
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    inner class Tokens {
        @Autowired
        lateinit var mockMvc: MockMvc

        @Autowired
        lateinit var tokens: DozyTestTokens

        @Test
        fun `테스트 토큰은 스타터 검증을 통과하고 role로 인가됨`() {
            val token = tokens.issue(roles = listOf("sample:item_manager"))

            mockMvc.get("/items") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        }

        @Test
        fun `이 서비스가 받지 않는 realm의 토큰은 401`() {
            val token = tokens.issue(type = PrincipalType.PARTNER)

            mockMvc.get("/me") { header("Authorization", "Bearer $token") }.andExpect { status { isUnauthorized() } }
        }
    }
}
