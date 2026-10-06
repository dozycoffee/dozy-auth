package com.dozycoffee.auth.server.config

import com.dozycoffee.auth.server.adapter.outbound.metrics.BuildInfoMeterBinder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * 실행 중인 서버의 버전 (configuration.md §12.1, ADR-0032).
 *
 * - `revision`: 이미지를 빌드한 커밋 SHA. 빌드할 때 `META-INF/build-info.properties`에 들어갑니다.
 * - `version`: 배포가 `AUTH_RELEASE_VERSION`(`dozy.auth.release-version`)으로 알려 준 릴리스 버전.
 *   없으면 빌드할 때 넣은 값(`sha-{7자리}`, 로컬 빌드는 `local`)입니다.
 *   릴리스는 이미지를 다시 빌드하지 않고 태그만 덧붙이므로 이미지 안에는 semver가 없습니다.
 *
 * 기동할 때 한 번 로그로 남기고 `dozy.auth.build.info` 지표로 내보냅니다. `/actuator/info`는 HTTP로 열지 않습니다 (§10.1).
 */
@Configuration(proxyBeanMethods = false)
class BuildInfoConfig {
    @Bean
    fun buildInfoMeterBinder(
        buildProperties: ObjectProvider<BuildProperties>,
        environment: Environment,
    ): BuildInfoMeterBinder {
        val build = buildProperties.ifAvailable
        val version =
            environment.getProperty(RELEASE_VERSION)?.takeIf { it.isNotBlank() }
                ?: build?.version
                ?: UNKNOWN
        val revision = build?.get(REVISION) ?: UNKNOWN
        log.info("Dozy Auth 서버 버전 {}, revision {}", version, revision)
        return BuildInfoMeterBinder(version, revision)
    }

    private companion object {
        const val RELEASE_VERSION = "dozy.auth.release-version"
        const val REVISION = "revision"

        /** build-info.properties 없이 실행할 때 (IDE에서 Gradle을 거치지 않고 실행 등). */
        const val UNKNOWN = "unknown"

        val log = LoggerFactory.getLogger(BuildInfoConfig::class.java)
    }
}
