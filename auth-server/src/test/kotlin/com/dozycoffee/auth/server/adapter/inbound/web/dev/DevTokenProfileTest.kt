package com.dozycoffee.auth.server.adapter.inbound.web.dev

import com.dozycoffee.auth.server.TestcontainersConfiguration
import com.dozycoffee.auth.server.support.TokenFixtures.EMPLOYEE
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertTrue

/** 개발용 API는 `local`·`dev`가 아닌 프로필에서 등록되지 않고 보안 설정도 막음 (api/dev.md, configuration.md §4). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class DevTokenProfileTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `local·dev가 아닌 프로필에서는 개발용 토큰 발급 컨트롤러가 없음`() {
        assertTrue(context.getBeansOfType(DevTokenController::class.java).isEmpty())
    }

    @Test
    fun `local·dev가 아닌 프로필에서는 개발용 토큰 발급을 인증 없이 호출할 수 없음`() {
        mockMvc
            .post("/dev/tokens") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"realm":"internal","principalType":"employee","principalId":"${EMPLOYEE.id}"}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHENTICATED") }
                jsonPath("$.accessToken") { doesNotExist() }
            }
    }
}
