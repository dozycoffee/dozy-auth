package com.dozycoffee.auth.server.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.env.MockEnvironment
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실행 중인 서버의 버전: 기동 로그와 `dozy.auth.build.info` 지표 (configuration.md §10.2, §12.1). */
@ExtendWith(OutputCaptureExtension::class)
class BuildInfoConfigTest {
    @Test
    fun `배포가 알려 준 릴리스 버전이 있으면 version은 그 값이고 revision은 빌드한 커밋`() {
        val tags = buildInfoTags(MockEnvironment().withProperty("dozy.auth.release-version", RELEASE_VERSION), buildProperties())

        assertEquals(mapOf("version" to RELEASE_VERSION, "revision" to REVISION), tags)
    }

    @Test
    fun `릴리스 버전이 없으면 version은 빌드할 때 넣은 값`() {
        val tags = buildInfoTags(MockEnvironment().withProperty("dozy.auth.release-version", ""), buildProperties())

        assertEquals(mapOf("version" to BUILD_VERSION, "revision" to REVISION), tags)
    }

    @Test
    fun `build-info가 없으면 version과 revision은 unknown`() {
        val tags = buildInfoTags(MockEnvironment(), buildProperties = null)

        assertEquals(mapOf("version" to "unknown", "revision" to "unknown"), tags)
    }

    @Test
    fun `기동할 때 버전과 revision을 로그로 남김`(output: CapturedOutput) {
        buildInfoTags(MockEnvironment().withProperty("dozy.auth.release-version", RELEASE_VERSION), buildProperties())

        val line = output.all.lines().last { it.contains("Dozy Auth 서버 버전") }
        assertTrue(line.contains(RELEASE_VERSION) && line.contains(REVISION), line)
    }

    /** 지표 값은 항상 1이고, 태그만 돌려줍니다. */
    private fun buildInfoTags(
        environment: MockEnvironment,
        buildProperties: BuildProperties?,
    ): Map<String, String> {
        val beans = StaticListableBeanFactory()
        buildProperties?.let { beans.addBean("buildProperties", it) }
        val registry = SimpleMeterRegistry()

        BuildInfoConfig()
            .buildInfoMeterBinder(beans.getBeanProvider(BuildProperties::class.java), environment)
            .bindTo(registry)

        val gauge = registry.get("dozy.auth.build.info").gauge()
        assertEquals(1.0, gauge.value())
        return gauge.id.tags.associate { it.key to it.value }
    }

    private fun buildProperties(): BuildProperties =
        BuildProperties(
            Properties().apply {
                setProperty("version", BUILD_VERSION)
                setProperty("revision", REVISION)
            },
        )

    private companion object {
        const val RELEASE_VERSION = "0.1.0"
        const val BUILD_VERSION = "sha-1a2b3c4"
        const val REVISION = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b"
    }
}
