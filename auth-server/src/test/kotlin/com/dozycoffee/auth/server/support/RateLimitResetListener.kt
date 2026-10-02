package com.dozycoffee.auth.server.support

import com.dozycoffee.auth.server.adapter.outbound.ratelimit.RateLimitBucket4jAdapter
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener

/**
 * 테스트 메서드마다 요청 제한 카운터를 비웁니다. `META-INF/spring.factories`로 모든 스프링 테스트에 등록합니다.
 *
 * 테스트는 같은 스프링 컨텍스트와 같은 클라이언트 주소(MockMvc는 `127.0.0.1`)를 함께 쓰므로, 비우지 않으면 다른 테스트의 요청이
 * 쌓여 인증 없는 API가 `429`로 끝날 수 있습니다. 요청 제한을 확인하는 테스트는 한 메서드 안에서 한도를 채웁니다.
 */
class RateLimitResetListener : TestExecutionListener {
    override fun beforeTestMethod(testContext: TestContext) {
        if (!testContext.hasApplicationContext()) return
        testContext.applicationContext.getBeanProvider(RateLimitBucket4jAdapter::class.java).ifAvailable { it.clear() }
    }
}
