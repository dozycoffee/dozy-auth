package com.dozycoffee.auth.server.adapter.outbound.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder

/**
 * 실행 중인 서버의 버전을 값이 항상 1인 gauge로 남깁니다 (configuration.md §10.2, ADR-0032).
 *
 * Prometheus에서 인스턴스마다 `dozy_auth_build_info{version, revision}` 한 줄이 나옵니다.
 * 값은 기동할 때 정해지므로 태그 조합은 인스턴스당 하나입니다.
 */
class BuildInfoMeterBinder(
    private val version: String,
    private val revision: String,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Gauge
            .builder(BUILD_INFO) { 1 }
            .description("실행 중인 Auth 서버의 버전과 커밋")
            .tags(VERSION, version, REVISION, revision)
            .register(registry)
    }

    companion object {
        const val BUILD_INFO = "dozy.auth.build.info"

        private const val VERSION = "version"
        private const val REVISION = "revision"
    }
}
