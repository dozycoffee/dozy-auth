package com.dozycoffee.auth.server.config.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertFailsWith

/** 개발용 API는 `prod`에서 켤 수 없음 (configuration.md §2, §4). */
class DevApiConfigTest {
    @ParameterizedTest
    @ValueSource(strings = ["local", "dev"])
    fun `prod 프로필에서 개발용 API가 켜지면 기동 실패`(devProfile: String) {
        val environment = MockEnvironment().apply { setActiveProfiles("prod", devProfile) }

        assertFailsWith<IllegalStateException> { DevApiConfig(environment) }
    }

    @Test
    fun `dev 프로필만 켜져 있으면 기동`() {
        DevApiConfig(MockEnvironment().apply { setActiveProfiles("dev") })
    }
}
